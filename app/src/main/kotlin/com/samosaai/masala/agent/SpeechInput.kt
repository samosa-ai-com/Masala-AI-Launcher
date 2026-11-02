package com.samosaai.masala.agent

import com.samosaai.masala.audio.AudioProvider
import com.samosaai.masala.audio.PttEngine
import com.samosaai.masala.audio.SpeechConfig
import com.samosaai.masala.i18n.Language
import java.util.Locale

/** Push-to-talk: [start] opens Sam listening, [stop] on Send returns the transcript. */
interface SpeechInput {
    /** False if listening couldn't start; [onFailure] says why when it fails later. */
    fun start(onFailure: (String) -> Unit): Boolean
    suspend fun stop(): Result<String>
    fun cancel()

    /** Reports a take that ended by itself (a failed capture, or the 5-minute cap). */
    fun setTakeAutoEndListener(listener: ((com.samosaai.masala.audio.PttEngine.TakeAutoEnd) -> Unit)?) {}

    /**
     * Mic level 0..1 while listening (UI refresh U4). Words are never streamed out while
     * listening: only the final transcript from [stop] reaches the chat.
     */
    val level: kotlinx.coroutines.flow.StateFlow<Float> get() = NO_LEVEL

    /** True once the live take is really capturing; the UI holds "Get ready…" until then. */
    val ready: kotlinx.coroutines.flow.StateFlow<Boolean> get() = NO_READY

    /**
     * Pause the live take without transcribing; [resume] continues it. Send/Cancel from the
     * paused state reuse [stop]/[cancel].
     */
    suspend fun pause(): Boolean = false
    fun resume(): Boolean = false
}

private val NO_LEVEL = kotlinx.coroutines.flow.MutableStateFlow(0f)
private val NO_READY = kotlinx.coroutines.flow.MutableStateFlow(false)

/**
 * Which provider push-to-talk tries, in order. The configured cloud STT goes first when
 * the app has one; the on-device recognizer is always the last resort, never the only try.
 * Pure so unit tests can pin the policy without an Android runtime.
 */
internal object SpeechPolicy {
    fun order(configured: AudioProvider): List<AudioProvider> = when {
        configured.isApiBased() -> listOf(configured, AudioProvider.ANDROID)
        else -> listOf(AudioProvider.ANDROID)
    }
}

/** User-facing reasons [ConfiguredSpeechInput] can report; loaded from resources by the caller. */
data class SpeechStrings(
    val localUnavailable: String,
    val recordFailed: String,
    /** Shown when the user stayed quiet. */
    val noSpeech: String = "No speech detected",
    /** Shown when speech was heard but nothing usable came back. */
    val didntCatch: String = "Didn't catch that",
    /** Shown when the recognizer is busy (usually a stuck session just ended; retry). */
    val busy: String = "The speech recognizer is busy. Try again.",
    /** Shown for transcription-server HTTP failures and network errors. */
    val server: String = "Couldn't reach the transcription server. Check your connection and try again.",
    /** Shown for authentication/authorization rejections. */
    val auth: String = "Transcription was rejected. Check your voice setup in Settings → Voice.",
    /** Shown when recording itself couldn't start. */
    val recordError: String = "Couldn't record from the microphone. Check the microphone permission.",
    /** Shown for anything else. */
    val generic: String = "Something went wrong with speech input. Try again.",
)

/**
 * Maps a raw STT failure to what the user should read. Engine messages stay machine-stable
 * (the voice-call loop classifies on them); only display goes through here.
 */
fun friendlySttMessage(raw: String?, s: SpeechStrings): String {
    val m = raw?.trim().orEmpty()
    if (m.isEmpty() || m == "No speech detected") return s.noSpeech
    if (m.startsWith("Speech recognition failed:")) {
        val code = m.removePrefix("Speech recognition failed:").trim().toIntOrNull()
        // 6 = speech timeout, 7 = no match: heard nothing usable; 8 = recognizer busy.
        return when (code) {
            6, 7 -> s.noSpeech
            8 -> s.busy
            else -> s.generic
        }
    }
    if (m.contains("HTTP 401") || m.contains("HTTP 403") || m.contains("Unauthor", ignoreCase = true)) return s.auth
    if (m.contains("HTTP") || m.contains("host", ignoreCase = true) || m.contains("timeout", ignoreCase = true) || m.contains("network", ignoreCase = true)) return s.server
    if (m.contains("No transcription model") || m.contains("not configured", ignoreCase = true)) return s.server
    if (m.contains("ecord", ignoreCase = true) || m.contains("permission", ignoreCase = true)) return s.recordError
    return s.generic
}

private fun phoneLanguage() = Language.entries.firstOrNull { it.matchesCode(Locale.getDefault().language) } ?: Language.ENGLISH

/** The STT language from settings, or the phone's when unset or unknown. */
fun speechLanguage(settings: SpeechConfig): Language =
    settings.sttLanguage.takeIf { it.isNotBlank() }?.let { code -> Language.entries.firstOrNull { it.matchesCode(code) } }
        ?: phoneLanguage()

/**
 * [SpeechInput] that follows the app's STT configuration: cloud transcription when
 * [SpeechConfig] names an endpoint, otherwise the on-device recognizer — with the
 * other one as fallback when the first can't start. A phone whose default recognizer
 * is a stub (or has none) therefore records for the server instead of failing.
 *
 * A tap starts an open-ended take; only Send (or cancel) ends it. The mic button and the
 * Settings test card both record this way.
 */
class ConfiguredSpeechInput(
    private val engine: PttEngine,
    private val config: () -> SpeechConfig,
    private val localAvailable: () -> Boolean,
    private val strings: SpeechStrings,
    private val live: com.samosaai.masala.audio.SttEngine? = null,
) : SpeechInput {
    override val level get() = live?.level ?: super.level
    override val ready: kotlinx.coroutines.flow.StateFlow<Boolean> get() = engine.captureReady

    /**
     * Pause the live take, keeping its audio/transcript for Send or [resume].
     * The active provider stays set: stopping or cancelling from the paused state works.
     */
    override suspend fun pause(): Boolean {
        val provider = active ?: return false
        return engine.pauseListening(provider)
    }

    override fun resume(): Boolean {
        val provider = active ?: return false
        return engine.resumeListening(provider)
    }

    @Volatile
    private var active: AudioProvider? = null

    private fun language() = phoneLanguage()

    private fun configuredLanguage(settings: SpeechConfig): Language = speechLanguage(settings)

    private fun configured(): AudioProvider =
        if (config().baseUrl.isNotBlank()) AudioProvider.API else AudioProvider.ANDROID

    /** The engine ended the take by itself; [onEngineTakeEnd] routes it. */
    private var failure: ((String) -> Unit)? = null
    private var autoEnd: ((PttEngine.TakeAutoEnd) -> Unit)? = null

    override fun setTakeAutoEndListener(listener: ((PttEngine.TakeAutoEnd) -> Unit)?) {
        autoEnd = listener
    }

    override fun start(onFailure: (String) -> Unit): Boolean {
        live?.resetLive()
        // A press while a previous session is still around must not leak its recorder.
        cancel()
        failure = onFailure
        // Attached before recording starts: a failure on the very first read must
        // already have somewhere to go. Cleared again on every exit below.
        engine.setTakeAutoEndListener(::onEngineTakeEnd)
        val reasons = mutableListOf<String>()
        val lang = configuredLanguage(config())
        for (provider in SpeechPolicy.order(configured())) {
            if (provider == AudioProvider.ANDROID && !localAvailable()) {
                reasons += strings.localUnavailable
                continue
            }
            val started = if (provider == AudioProvider.ANDROID) {
                engine.startListening(provider, lang, onFailure)
            } else {
                engine.startListening(provider, lang)
            }
            if (started) {
                active = provider
                return true
            }
            reasons += if (provider == AudioProvider.ANDROID) {
                strings.localUnavailable
            } else {
                strings.recordFailed
            }
        }
        active = null
        engine.setTakeAutoEndListener(null)
        onFailure(reasons.joinToString("; "))
        return false
    }

    override suspend fun stop(): Result<String> {
        val provider = active ?: return Result.failure(Exception("Wasn't listening"))
        active = null
        failure = null
        autoEnd = null
        engine.setTakeAutoEndListener(null)
        val settings = config()
        val model = if (provider.isApiBased()) settings.sttModel else ""
        val language = if (provider.isApiBased()) settings.sttLanguage.ifBlank { language().iso639 } else ""
        return engine.stopListeningAndTranscribe(provider, model, language)
    }

    /**
     * The engine ended the take by itself: a failure reports at once so the UI never
     * sits on "Listening…" for a dead take; the cap forwards for Send-with-what-we-have.
     * One-shot: the first event wins and clears the rest.
     */
    private fun onEngineTakeEnd(end: PttEngine.TakeAutoEnd) {
        engine.setTakeAutoEndListener(null)
        when (end) {
            is PttEngine.TakeAutoEnd.Failed -> {
                autoEnd = null
                failure?.invoke(end.message)
            }
            PttEngine.TakeAutoEnd.CapReached -> autoEnd?.invoke(end).also { autoEnd = null }
        }
    }

    override fun cancel() {
        active = null
        failure = null
        autoEnd = null
        engine.setTakeAutoEndListener(null)
        engine.cancelListening(AudioProvider.ANDROID)
        engine.cancelListening(AudioProvider.API)
    }
}
