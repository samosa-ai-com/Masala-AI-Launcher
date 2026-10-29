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
    ) : PttEngine {
        val started = mutableListOf<AudioProvider>()
        val cancelled = mutableListOf<AudioProvider>()
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
}
