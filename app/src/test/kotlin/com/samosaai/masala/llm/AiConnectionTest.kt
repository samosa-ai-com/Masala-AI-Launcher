package com.samosaai.masala.llm

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AiConnectionTest {
    private val body = """
        {"data":[
          {"id":"muse-spark-1.3","task":"chat"},
          {"id":"muse-voice-transcribe-1.0","task":"automatic-speech-recognition"},
          {"id":"kokoro","task":"text-to-speech"},
          {"id":"whisper-large-v3"},
          {"id":"gpt-4o-mini-tts"},
          {"id":"llama-3.3-70b"}
        ]}
    """.trimIndent()

    @Test
    fun `models sort into chat, transcription and voices`() {
        val found = AiConnection.classify(body, 12)
        assertEquals(listOf("llama-3.3-70b", "muse-spark-1.3"), found.chat)
        assertEquals(listOf("muse-voice-transcribe-1.0", "whisper-large-v3"), found.transcription)
        assertEquals(listOf("gpt-4o-mini-tts", "kokoro"), found.voices)
    }

    @Test
    fun `a damaged reply finds nothing rather than failing`() {
        assertEquals(emptyList(), AiConnection.classify("<html>", 0).chat)
    }

    @Test
    fun `test sends the key, reports a refused key, and a connection with what it found`(): Unit = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody(body))
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            server.start()
            val base = server.url("/v1/").toString()
            val ok = AiConnection().test(base, "sk-1")
            assertIs<AiConnection.Outcome.Connected>(ok)
            assertEquals(2, ok.found.chat.size)
            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer sk-1", request.getHeader("Authorization"))
            assertIs<AiConnection.Outcome.KeyRefused>(AiConnection().test(base, "bad"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `no key sends no authorization header`(): Unit = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            server.start()
            AiConnection().test(server.url("/v1").toString(), "")
            assertEquals(null, server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
