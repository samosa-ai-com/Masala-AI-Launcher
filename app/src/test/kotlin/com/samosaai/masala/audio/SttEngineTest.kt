package com.samosaai.masala.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Covers [SttEngine.isBenignSttError] — the classification a hands-free listen
 * loop uses to tell "the user stayed quiet" apart from a genuine STT failure.
 */
class SttEngineTest {

    // Android SpeechRecognizer.ERROR_* values (kept as literals so this pure
    // unit test never touches the android.jar stubs).
    private val errorSpeechTimeout = 6
    private val errorNoMatch = 7
    private val errorClient = 5
    private val errorRecognizerBusy = 8
    private val errorInsufficientPermissions = 9

    @Test
    fun `a blank success result is benign silence`() {
        assertTrue(SttEngine.isBenignSttError(null))
    }

    @Test
    fun `no speech detected is benign silence`() {
        assertTrue(SttEngine.isBenignSttError(Exception("No speech detected")))
    }

    @Test
    fun `recognizer no-match and speech-timeout errors are benign silence`() {
        assertTrue(SttEngine.isBenignSttError(Exception("Speech recognition failed: $errorSpeechTimeout")))
        assertTrue(SttEngine.isBenignSttError(Exception("Speech recognition failed: $errorNoMatch")))
    }

    @Test
    fun `real recognizer errors are not benign`() {
        assertFalse(SttEngine.isBenignSttError(Exception("Speech recognition failed: $errorClient")))
        assertFalse(SttEngine.isBenignSttError(Exception("Speech recognition failed: $errorRecognizerBusy")))
        assertFalse(SttEngine.isBenignSttError(Exception("Speech recognition failed: $errorInsufficientPermissions")))
    }

    @Test
    fun `api and network failures are not benign`() {
        assertFalse(SttEngine.isBenignSttError(Exception("API not configured")))
        assertFalse(SttEngine.isBenignSttError(IOException("Network problem: could not reach the server")))
        assertFalse(SttEngine.isBenignSttError(Exception("HTTP 401 Authentication failed")))
    }

    @Test
    fun `an error without a message is not benign`() {
        assertFalse(SttEngine.isBenignSttError(Exception()))
    }

    @Test
    fun `detector speech with audio is kept`() {
        assertTrue(SttEngine.shouldKeepRecording(speechDetected = true, userStopped = false, pcmBytes = 6400))
    }

    @Test
    fun `user stop with audio is kept even when the detector never fired`() {
        assertTrue(SttEngine.shouldKeepRecording(speechDetected = false, userStopped = true, pcmBytes = 6400))
    }

    @Test
    fun `pure silence is dropped even on user stop`() {
        assertFalse(SttEngine.shouldKeepRecording(speechDetected = false, userStopped = true, pcmBytes = 0))
    }

    @Test
    fun `unaccepted audio without a stop is dropped`() {
        assertFalse(SttEngine.shouldKeepRecording(speechDetected = false, userStopped = false, pcmBytes = 6400))
    }

    @Test
    fun `leading zeros fail fast only from 1_5 s`() {
        assertFalse(SttEngine.zeroRunFailsFast(0))
        assertFalse(SttEngine.zeroRunFailsFast(1_400))
        assertTrue(SttEngine.zeroRunFailsFast(1_500))
        assertTrue(SttEngine.zeroRunFailsFast(1_600))
    }
}
