package com.samosaai.masala.agent

import com.samosaai.masala.audio.AudioProvider
import com.samosaai.masala.audio.SttEngine
import com.samosaai.masala.i18n.Language
import java.util.Locale

/** Push-to-talk: [start] while the button is held, [stop] on release returns the transcript. */
interface SpeechInput {
    /** False if listening couldn't start; [onFailure] says why when it fails later. */
    fun start(onFailure: (String) -> Unit): Boolean
    suspend fun stop(): Result<String>
    fun cancel()
}

/** [SpeechInput] on Android's own recognizer, in the phone's language when Masala supports it. */
class AndroidSpeechInput(private val stt: SttEngine) : SpeechInput {
    private fun language() = Language.entries.firstOrNull { it.matchesCode(Locale.getDefault().language) } ?: Language.ENGLISH

    override fun start(onFailure: (String) -> Unit) = stt.startListening(AudioProvider.ANDROID, language(), onFailure)

    override suspend fun stop() = stt.stopListeningAndTranscribe(AudioProvider.ANDROID, "")

    override fun cancel() = stt.cancelListening(AudioProvider.ANDROID)
}
