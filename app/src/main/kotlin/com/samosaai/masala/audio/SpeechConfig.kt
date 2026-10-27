package com.samosaai.masala.audio

import com.samosaai.masala.BuildConfig

/**
 * Where speech goes: an OpenAI-compatible `/audio/transcriptions` endpoint. From `local.properties`
 * (`MASALA_STT_*`), falling back to the model endpoint. An empty [sttModel] means "ask the server".
 * Stands in for the speech part of the private prototype's settings until Masala has a speech settings screen.
 */
data class SpeechConfig(val baseUrl: String, val apiKey: String, val sttModel: String, val sttLanguage: String = "") {
    companion object {
        val default = SpeechConfig(BuildConfig.MASALA_STT_BASE_URL, BuildConfig.MASALA_STT_API_KEY, BuildConfig.MASALA_STT_MODEL)
    }
}
