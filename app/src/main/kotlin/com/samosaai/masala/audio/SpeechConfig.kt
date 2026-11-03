package com.samosaai.masala.audio

import com.samosaai.masala.BuildConfig

/**
 * Where speech goes: an OpenAI-compatible `/audio/transcriptions` endpoint, or nowhere (blank
 * [baseUrl]) when voice input is "On this phone". [default] is the voice-in choice in Settings → Voice
 * (`AiSettingsStore`), seeded once from `local.properties` (`MASALA_STT_*`, falling back to the model
 * endpoint). An empty [sttModel] means "ask the server".
 */
data class SpeechConfig(val baseUrl: String, val apiKey: String, val sttModel: String, val sttLanguage: String = "") {
    companion object {
        val fromBuild get() = SpeechConfig(BuildConfig.MASALA_STT_BASE_URL, BuildConfig.MASALA_STT_API_KEY, BuildConfig.MASALA_STT_MODEL)

        /** Set by `MasalaApp` to read the store; build config until then (and in unit tests). */
        @Volatile
        var source: () -> SpeechConfig = { fromBuild }

        val default: SpeechConfig get() = source()
    }
}
