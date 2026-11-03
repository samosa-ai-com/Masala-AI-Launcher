package com.samosaai.masala.audio

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The endpoint offers `muse-voice-transcribe-1.0`. Classifying it as TTS ("voice") meant the app
 * never picked a transcription model, so `transcribe_file` could not work at all.
 */
class AudioModelClassifyTest {
    @Test
    fun `a voice-transcribe model is speech to text, not text to speech`() {
        assertEquals(ModelCategory.STT, AudioModel.categorize("muse-voice-transcribe-1.0"))
    }

    @Test
    fun `the usual names still classify correctly`() {
        assertEquals(ModelCategory.STT, AudioModel.categorize("whisper-large-v3"))
        assertEquals(ModelCategory.STT, AudioModel.categorize("some-transcription-model"))
        assertEquals(ModelCategory.TTS, AudioModel.categorize("kokoro-tts"))
        assertEquals(ModelCategory.TTS, AudioModel.categorize("muse-voice-1.0"))
        // A language model is neither speech-to-text nor text-to-speech.
        assertEquals(ModelCategory.UNKNOWN, AudioModel.categorize("muse-spark-1.3-contributor"))
    }

    @Test
    fun `image models classify as image, never chat`() {
        assertEquals(ModelCategory.IMAGE, AudioModel.categorize("muse-image-1.0"))
        assertEquals(ModelCategory.IMAGE, AudioModel.categorize("dall-e-3", task = "image-generation"))
        assertEquals(ModelCategory.IMAGE, AudioModel.categorize("mystery-model", task = "image-generation"))
    }

    @Test
    fun `an explicit task hint still wins`() {
        assertEquals(ModelCategory.STT, AudioModel.categorize("mystery-model", task = "transcription"))
        assertEquals(ModelCategory.TTS, AudioModel.categorize("mystery-model", task = "text-to-speech"))
    }
}
