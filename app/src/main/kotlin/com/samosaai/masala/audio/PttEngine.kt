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

    /**
     * How a take ended by itself, without Send or cancel: the capture failed mid-take,
     * or the safety cap stopped it. The take still has to leave Listening, so the engine
     * reports it instead of waiting for a Send that may never come.
     */
    sealed interface TakeAutoEnd {
        /** Capture failed; the message is what the user should read. */
        data class Failed(val message: String) : TakeAutoEnd

        /** The safety cap stopped the capture; what was heard should still be sent. */
        data object CapReached : TakeAutoEnd
    }

    /** Reports [TakeAutoEnd]; null stops reports. Default no-op, so fakes ignore it. */
    fun setTakeAutoEndListener(listener: ((TakeAutoEnd) -> Unit)?) {}

    /**
     * Tap to talk (plan UI refresh U2): listen until a pause of [silenceTimeoutMs] after speech, then
     * transcribe. [finishUtterance] ends it early and still transcribes what was said. No caller
     * today: the mic and the Settings test card both end a take only on Send/Stop.
     */
    suspend fun listenForUtterance(
        provider: AudioProvider,
        model: String,
        language: Language = Language.ENGLISH,
        silenceTimeoutMs: Long,
    ): Result<String> = Result.failure(UnsupportedOperationException("Tap to talk isn't available"))

    fun finishUtterance() {}
}
