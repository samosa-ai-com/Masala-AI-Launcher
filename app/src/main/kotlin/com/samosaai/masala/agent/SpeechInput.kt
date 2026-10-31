package com.samosaai.masala.agent

import com.samosaai.masala.audio.AudioProvider
import com.samosaai.masala.audio.PttEngine
import com.samosaai.masala.audio.SpeechConfig
import com.samosaai.masala.i18n.Language
import java.util.Locale

/** Push-to-talk: [start] while the button is held, [stop] on release returns the transcript. */
interface SpeechInput {
    /** False if listening couldn't start; [onFailure] says why when it fails later. */
    fun start(onFailure: (String) -> Unit): Boolean
    suspend fun stop(): Result<String>
    fun cancel()

    /**
     * Hands-free: listens until the speaker pauses, then returns the transcript. Only the
     * providers that can do this (an endpoint we time ourselves) are asked to.
     */
    suspend fun listenOnce(): Result<String> = Result.failure(UnsupportedOperationException("Hands-free isn't available"))

    /** Ends [listenOnce] now; what was said so far is still transcribed. */
    fun finish() {}

    /** Mic level 0..1 while listening (UI refresh U4). */
    val level: kotlinx.coroutines.flow.StateFlow<Float> get() = NO_LEVEL

    /** Words heard so far, where the recognizer reports them. */
    val partial: kotlinx.coroutines.flow.StateFlow<String> get() = NO_WORDS

    /** Seconds until the take ends by itself, where the provider works that way; else null. */
    val autoSendIn: kotlinx.coroutines.flow.StateFlow<Int?> get() = NO_COUNTDOWN
}

private val NO_LEVEL = kotlinx.coroutines.flow.MutableStateFlow(0f)
private val NO_WORDS = kotlinx.coroutines.flow.MutableStateFlow("")
private val NO_COUNTDOWN = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

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

    /**
     * Whether a tap can start a hands-free take that ends by itself. The server path is
     * timed by our own voice detector, so it can. The on-device recognizer ends a take on
     * its own schedule (and reopens the microphone between segments), so a hands-free take
     * there either cuts the speaker off or flickers the mic on and off; it is hold-to-talk
     * only, which is also how it is set by default.
     */
    fun handsFreeWorks(provider: AudioProvider): Boolean = provider.isApiBased()
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

/**
 * Silence that ends a hands-free turn: 2.5 s, so a breath or a slow sentence does not
 * cut the user off mid-thought. Hold-to-talk never depends on it (release sends).
 */
private const val SILENCE_MS = 2500L

/**
 * [SpeechInput] that follows the app's STT configuration: cloud transcription when
 * [SpeechConfig] names an endpoint, otherwise the on-device recognizer — with the
 * other one as fallback when the first can't start. A phone whose default recognizer
 * is a stub (or has none) therefore records for the server instead of failing.
 *
 * Hands-free (a tap that ends by itself) runs only where it can be timed reliably: the
 * server path, through our own voice detector. Everywhere else the mic is hold-to-talk,
 * which is what the on-device recognizer can do well.
 */
class ConfiguredSpeechInput(
    private val engine: PttEngine,
    private val config: () -> SpeechConfig,
    private val localAvailable: () -> Boolean,
    private val strings: SpeechStrings,
    private val live: com.samosaai.masala.audio.SttEngine? = null,
    /** The user's choice for hands-free; defaults to the provider's ability. */
    private val handsFreeEnabled: () -> Boolean = { true },
) : SpeechInput {
    override val level get() = live?.level ?: super.level
    override val partial get() = live?.partial ?: super.partial
    override val autoSendIn get() = live?.autoSendIn ?: super.autoSendIn

    @Volatile
    private var active: AudioProvider? = null

    /** This session started hands-free (and will end by itself) rather than from a hold. */
    @Volatile
    private var handsFree = false

    private fun language() = Language.entries.firstOrNull { it.matchesCode(Locale.getDefault().language) } ?: Language.ENGLISH

    /** The STT language from settings, or the phone's when unset or unknown. */
    private fun configuredLanguage(settings: SpeechConfig): Language =
        settings.sttLanguage.takeIf { it.isNotBlank() }?.let { code -> Language.entries.firstOrNull { it.matchesCode(code) } }
            ?: language()

    private fun configured(): AudioProvider =
        if (config().baseUrl.isNotBlank()) AudioProvider.API else AudioProvider.ANDROID

    /** Whether a tap may start a take that ends itself with this configuration. */
    fun handsFreeAvailable(): Boolean = handsFreeEnabled() && SpeechPolicy.handsFreeWorks(configured())

    override fun start(onFailure: (String) -> Unit): Boolean {
        live?.resetLive()
        // A press while a previous session is still around must not leak its recorder.
        cancel()
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
                handsFree = false
                return true
            }
            reasons += if (provider == AudioProvider.ANDROID) {
                strings.localUnavailable
            } else {
                strings.recordFailed
            }
        }
        active = null
        onFailure(reasons.joinToString("; "))
        return false
    }

    override suspend fun stop(): Result<String> {
        val provider = active ?: return Result.failure(Exception("Wasn't listening"))
        active = null
        handsFree = false
        val settings = config()
        val model = if (provider.isApiBased()) settings.sttModel else ""
        val language = if (provider.isApiBased()) settings.sttLanguage.ifBlank { language().iso639 } else ""
        return engine.stopListeningAndTranscribe(provider, model, language)
    }

    override suspend fun listenOnce(): Result<String> {
        cancel()
        live?.resetLive()
        val settings = config()
        // Only the providers we can time ourselves; on-device stays hold-to-talk.
        val order = SpeechPolicy.order(configured())
            .filter { SpeechPolicy.handsFreeWorks(it) }
            .filter { it != AudioProvider.ANDROID || localAvailable() }
        if (order.isEmpty()) return Result.failure(Exception(strings.localUnavailable))
        val lang = configuredLanguage(settings)
        var last: Result<String>? = null
        // The first technical failure, kept: once the user has spoken, a fallback provider
        // only hears the room, and reporting its silence would hide the real error.
        var firstFailure: Result<String>? = null
        for (provider in order) {
            active = provider
            handsFree = true
            val model = if (provider.isApiBased()) settings.sttModel else ""
            val result = try {
                engine.listenForUtterance(provider, model, lang, SILENCE_MS)
            } finally {
                active = null
                handsFree = false
            }
            if (result.isSuccess) return result
            if (com.samosaai.masala.audio.SttEngine.isBenignSttError(result.exceptionOrNull())) {
                // The fallback heard nothing because the speaking already happened: the
                // server's failure is the answer, not "no speech detected".
                return firstFailure ?: result
            }
            if (firstFailure == null) firstFailure = result
            last = result
        }
        return firstFailure ?: last!!
    }

    override fun finish() = engine.finishUtterance()

    override fun cancel() {
        active = null
        handsFree = false
        engine.cancelListening(AudioProvider.ANDROID)
        engine.cancelListening(AudioProvider.API)
    }
}
