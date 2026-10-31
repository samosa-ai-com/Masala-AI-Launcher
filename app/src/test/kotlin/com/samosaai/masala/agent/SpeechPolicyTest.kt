package com.samosaai.masala.agent

import com.samosaai.masala.audio.AudioProvider
import com.samosaai.masala.audio.PttEngine
import com.samosaai.masala.audio.SpeechConfig
import com.samosaai.masala.i18n.Language
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The STT policy: configured cloud first, on-device always the last resort. */
class SpeechPolicyTest {
    @Test
    fun apiConfiguredTriesCloudThenLocal() {
        assertEquals(
            listOf(AudioProvider.API, AudioProvider.ANDROID),
            SpeechPolicy.order(AudioProvider.API),
        )
    }

    @Test
    fun samosaConfiguredTriesCloudThenLocal() {
        assertEquals(
            listOf(AudioProvider.SAMOSA_AI, AudioProvider.ANDROID),
            SpeechPolicy.order(AudioProvider.SAMOSA_AI),
        )
    }

    @Test
    fun nothingConfiguredIsLocalOnly() {
        assertEquals(listOf(AudioProvider.ANDROID), SpeechPolicy.order(AudioProvider.ANDROID))
        assertEquals(listOf(AudioProvider.ANDROID), SpeechPolicy.order(AudioProvider.NONE))
    }

    private class FakeEngine(
        var startResults: Map<AudioProvider, Boolean> = mapOf(),
        var stopText: String = "hello",
        var utteranceResults: Map<AudioProvider, Result<String>> = mapOf(),
    ) : PttEngine {
        val started = mutableListOf<AudioProvider>()
        val cancelled = mutableListOf<AudioProvider>()
        val utterances = mutableListOf<AudioProvider>()
        var lastSilenceMs = 0L
        var stopped: AudioProvider? = null
        var stoppedModel = ""
        var stoppedLanguage = ""
        var failStop: String? = null

        override fun startListening(
            provider: AudioProvider,
            language: Language,
            onAndroidFailure: ((String) -> Unit)?,
        ): Boolean {
            started += provider
            return startResults[provider] ?: true
        }

        override suspend fun stopListeningAndTranscribe(
            provider: AudioProvider,
            model: String,
            language: String,
        ): Result<String> {
            stopped = provider
            stoppedModel = model
            stoppedLanguage = language
            return failStop?.let { Result.failure(Exception(it)) } ?: Result.success(stopText)
        }

        override fun cancelListening(provider: AudioProvider) {
            cancelled += provider
        }

        override suspend fun listenForUtterance(
            provider: AudioProvider,
            model: String,
            language: com.samosaai.masala.i18n.Language,
            silenceTimeoutMs: Long,
        ): Result<String> {
            utterances += provider
            lastSilenceMs = silenceTimeoutMs
            return utteranceResults[provider] ?: Result.failure(UnsupportedOperationException("no stub"))
        }
    }

    private fun input(
        engine: FakeEngine,
        baseUrl: String = "https://stt.example/v1",
        localAvailable: Boolean = true,
    ) = ConfiguredSpeechInput(
        engine,
        { SpeechConfig(baseUrl, "key", "model", "") },
        { localAvailable },
        SpeechStrings(localUnavailable = "no-local", recordFailed = "no-record"),
    )

    @Test
    fun cloudPrimaryStartsCloudAndStopsWithModel() = runBlocking<Unit> {
        val engine = FakeEngine()
        val speech = input(engine)
        var failure: String? = null
        assertTrue(speech.start { failure = it })
        assertEquals(null, failure)
        assertEquals(listOf(AudioProvider.API), engine.started)
        val text = speech.stop()
        assertTrue(text.isSuccess)
        assertEquals(AudioProvider.API, engine.stopped)
        assertEquals("model", engine.stoppedModel)
    }

    @Test
    fun cloudStartFailureFallsBackToLocal() = runBlocking<Unit> {
        val engine = FakeEngine(startResults = mapOf(AudioProvider.API to false))
        val speech = input(engine)
        assertTrue(speech.start { })
        assertEquals(listOf(AudioProvider.API, AudioProvider.ANDROID), engine.started)
        speech.stop()
        assertEquals(AudioProvider.ANDROID, engine.stopped)
    }

    @Test
    fun missingLocalServiceSkipsStraightToNothingWhenUnconfigured() {
        val engine = FakeEngine()
        val speech = input(engine, baseUrl = "", localAvailable = false)
        var failure: String? = null
        assertFalse(speech.start { failure = it })
        assertEquals("no-local", failure)
        assertTrue(engine.started.isEmpty())
    }

    @Test
    fun bothProvidersFailingReportsBothReasons() {
        val engine = FakeEngine(startResults = mapOf(AudioProvider.API to false, AudioProvider.ANDROID to false))
        val speech = input(engine)
        var failure: String? = null
        assertFalse(speech.start { failure = it })
        assertEquals("no-record; no-local", failure)
    }

    @Test
    fun startResetsAStaleSessionFirst() {        val engine = FakeEngine(startResults = mapOf(AudioProvider.API to false, AudioProvider.ANDROID to false))
        val speech = input(engine)
        assertFalse(speech.start { })
        // Both providers were cancelled up front and neither stayed active.
        assertTrue(engine.cancelled.containsAll(listOf(AudioProvider.ANDROID, AudioProvider.API)))
        val stop = runBlocking { speech.stop() }
        assertTrue(stop.isFailure)
    }

    @Test
    fun cancelClearsTheActiveProvider() {
        val engine = FakeEngine()
        val speech = input(engine)
        assertTrue(speech.start { })
        speech.cancel()
        val stop = runBlocking { speech.stop() }
        assertTrue(stop.isFailure)
    }

    @Test
    fun tapCloudSuccessUsesCloudOnly() = runBlocking<Unit> {
        val engine = FakeEngine(utteranceResults = mapOf(AudioProvider.API to Result.success("open youtube")))
        val text = input(engine).listenOnce()
        assertTrue(text.isSuccess)
        assertEquals("open youtube", text.getOrNull())
        assertEquals(listOf(AudioProvider.API), engine.utterances)
    }

    @Test
    fun tapCloudFailureIsReportedInsteadOfRelistening() = runBlocking<Unit> {
        // The user already spoke during the server take; asking the phone's recognizer to
        // listen again would only hear the room and hide the real error.
        val engine = FakeEngine(
            utteranceResults = mapOf(
                AudioProvider.API to Result.failure(Exception("HTTP 500 boom")),
                AudioProvider.ANDROID to Result.success("never asked"),
            )
        )
        val text = input(engine).listenOnce()
        assertTrue(text.isFailure)
        assertEquals("HTTP 500 boom", text.exceptionOrNull()?.message)
        assertEquals(listOf(AudioProvider.API), engine.utterances)
    }

    @Test
    fun tapSilenceDoesNotRepeatTheWaitOnLocal() = runBlocking<Unit> {
        val engine = FakeEngine(
            utteranceResults = mapOf(
                AudioProvider.API to Result.failure(Exception("No speech detected")),
                AudioProvider.ANDROID to Result.success("should not be asked"),
            )
        )
        val text = input(engine).listenOnce()
        assertTrue(text.isFailure)
        assertEquals(listOf(AudioProvider.API), engine.utterances)
    }

    @Test
    fun tapWaitsLongEnoughForASlowSentence() = runBlocking<Unit> {
        val engine = FakeEngine(utteranceResults = mapOf(AudioProvider.API to Result.success("hi")))
        input(engine).listenOnce()
        // 2.5 s: a breath mid-sentence must not end the take (it was 1.5 s).
        assertEquals(2500L, engine.lastSilenceMs)
    }

    @Test
    fun `a server failure is reported, not the fallback's silence`() = runBlocking<Unit> {
        // The user spoke during the server take; the fallback only hears the room. The
        // reason the user sees must be the server error, not "no speech detected".
        val engine = FakeEngine(
            utteranceResults = mapOf(
                AudioProvider.API to Result.failure(Exception("HTTP 401 rejected")),
                AudioProvider.ANDROID to Result.failure(Exception("No speech detected")),
            )
        )
        val result = input(engine).listenOnce()
        assertTrue(result.isFailure)
        assertEquals("HTTP 401 rejected", result.exceptionOrNull()?.message)
    }

    @Test
    fun `listenOnce never reaches the on-device recognizer`() = runBlocking<Unit> {
        val engine = FakeEngine(
            utteranceResults = mapOf(
                AudioProvider.ANDROID to Result.success("should not be asked"),
            )
        )
        // On-device only: nothing can be timed, so the test card fails instead of half-listening.
        // (The mic itself never ends a take: only Send does.)
        val result = input(engine, baseUrl = "").listenOnce()
        assertTrue(result.isFailure)
        assertTrue(engine.utterances.isEmpty())
    }

    @Test
    fun friendlyMessagesNameTheFix() {
        val s = SpeechStrings("no-local", "no-record")
        assertEquals(s.noSpeech, friendlySttMessage(null, s))
        assertEquals(s.noSpeech, friendlySttMessage("No speech detected", s))
        assertEquals(s.noSpeech, friendlySttMessage("Speech recognition failed: 7", s))
        assertEquals(s.busy, friendlySttMessage("Speech recognition failed: 8", s))
        assertEquals(s.auth, friendlySttMessage("HTTP 401 body", s))
        assertEquals(s.server, friendlySttMessage("HTTP 500 boom", s))
        assertEquals(s.recordError, friendlySttMessage("Recording failed to start", s))
        assertEquals(s.generic, friendlySttMessage("something exotic", s))
    }
}
