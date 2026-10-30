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

    /** Tap to talk: listens until the speaker pauses, then returns the transcript. */
    suspend fun listenOnce(): Result<String> = Result.failure(UnsupportedOperationException("Tap to talk isn't available"))

    /** Ends [listenOnce] now; what was said so far is still transcribed. */
    fun finish() {}
}

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
)

/**
 * [SpeechInput] that follows the app's STT configuration: cloud transcription when
 * [SpeechConfig] names an endpoint, otherwise the on-device recognizer — with the
 * other one as fallback when the first can't start. A phone whose default recognizer
 * is a stub (or has none) therefore records for the server instead of failing.
 */
private const val SILENCE_MS = 1200L

class ConfiguredSpeechInput(
    private val engine: PttEngine,
    private val config: () -> SpeechConfig,
    private val localAvailable: () -> Boolean,
    private val strings: SpeechStrings,
) : SpeechInput {
    @Volatile
    private var active: AudioProvider? = null

    private fun language() = Language.entries.firstOrNull { it.matchesCode(Locale.getDefault().language) } ?: Language.ENGLISH

    private fun configured(): AudioProvider =
        if (config().baseUrl.isNotBlank()) AudioProvider.API else AudioProvider.ANDROID

    override fun start(onFailure: (String) -> Unit): Boolean {
        // A press while a previous session is still around must not leak its recorder.
        cancel()
        val reasons = mutableListOf<String>()
        for (provider in SpeechPolicy.order(configured())) {
            if (provider == AudioProvider.ANDROID && !localAvailable()) {
                reasons += strings.localUnavailable
                continue
            }
            val started = if (provider == AudioProvider.ANDROID) {
                engine.startListening(provider, language(), onFailure)
            } else {
                engine.startListening(provider, language())
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
        onFailure(reasons.joinToString("; "))
        return false
    }

    override suspend fun stop(): Result<String> {
        val provider = active ?: return Result.failure(Exception("Wasn't listening"))
        active = null
        val settings = config()
        val model = if (provider.isApiBased()) settings.sttModel else ""
        val language = if (provider.isApiBased()) settings.sttLanguage.ifBlank { language().iso639 } else ""
        return engine.stopListeningAndTranscribe(provider, model, language)
    }

    override suspend fun listenOnce(): Result<String> {
        cancel()
        val settings = config()
        val provider = SpeechPolicy.order(configured()).firstOrNull { it != AudioProvider.ANDROID || localAvailable() }
            ?: return Result.failure(Exception(strings.localUnavailable))
        active = provider
        val model = if (provider.isApiBased()) settings.sttModel else ""
        val lang = settings.sttLanguage.takeIf { it.isNotBlank() }?.let { code -> Language.entries.firstOrNull { it.matchesCode(code) } } ?: language()
        return try {
            engine.listenForUtterance(provider, model, lang, SILENCE_MS)
        } finally {
            active = null
        }
    }

    override fun finish() = engine.finishUtterance()

    override fun cancel() {
        active = null
        engine.cancelListening(AudioProvider.ANDROID)
        engine.cancelListening(AudioProvider.API)
    }
}
