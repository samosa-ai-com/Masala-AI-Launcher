package com.samosaai.masala.audio

import com.samosaai.masala.i18n.Language

/**
 * The push-to-talk surface of [SttEngine]: start listening with a provider, stop and
 * transcribe, cancel. Extracted so the provider policy ([ConfiguredSpeechInput]) is
 * unit-testable with a fake instead of the real recorder and recognizer.
 */
interface PttEngine {
    fun startListening(
        provider: AudioProvider,
        language: Language = Language.ENGLISH,
        onAndroidFailure: ((String) -> Unit)? = null,
    ): Boolean

    suspend fun stopListeningAndTranscribe(
        provider: AudioProvider,
        model: String,
        language: String = "",
    ): Result<String>

    fun cancelListening(provider: AudioProvider)
}
