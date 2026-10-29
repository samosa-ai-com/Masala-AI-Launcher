package com.samosaai.masala.layout

import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.LLMClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import java.util.concurrent.TimeUnit

class LlmChatCallerTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    private fun client() = LLMClient(apiKey = "test-key", baseUrl = server.url("/").toString())

    private fun chatBody(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}]}"""

    @Test
    fun `assistant text is returned`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(chatBody("hello")).addHeader("Content-Type", "application/json"))
        val out = LlmChatCaller(client()).call(
            listOf(ChatMessage("user", JsonPrimitive("hi"))), null, 3000, null
        )
        assertEquals("hello", out)
    }

    @Test
    fun `empty choices throws IllegalStateException`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"choices":[]}""").addHeader("Content-Type", "application/json"))
        assertFailsWith<IllegalStateException> {
            LlmChatCaller(client()).call(listOf(ChatMessage("user", JsonPrimitive("hi"))), null, 3000, null)
        }
    }

    @Test
    fun `http errors surface as HttpException`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad schema"}"""))
        assertFailsWith<retrofit2.HttpException> {
            LlmChatCaller(client()).call(listOf(ChatMessage("user", JsonPrimitive("hi"))), null, 3000, null)
        }
    }

    @Test
    fun `blank reply passes through to the checker`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(chatBody("")).addHeader("Content-Type", "application/json"))
        val out = LlmChatCaller(client()).call(listOf(ChatMessage("user", JsonPrimitive("hi"))), null, 3000, null)
        assertEquals("", out)
    }

    @Test
    fun `max tokens and temperature reach the wire`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(chatBody("ok")).addHeader("Content-Type", "application/json"))
        LlmChatCaller(client()).call(
            listOf(ChatMessage("user", JsonPrimitive("hi"))),
            responseFormat = null,
            maxTokens = 3000,
            temperature = 0.2f,
        )
        val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue(body.contains("\"max_tokens\":3000"), body)
        assertTrue(body.contains("\"temperature\":0.2"), body)
    }
}
