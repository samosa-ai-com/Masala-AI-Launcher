package com.samosaai.masala.agent

import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.visionUserMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Old screenshots leave the request; the newest ones and all text stay. */
class AgentHistoryCullTest {
    private fun shot(text: String, seed: Byte = 1) =
        visionUserMessage(text, seed.toString().repeat(40))

    @Test fun `an oversized message is cut before it can blow the window`() {
        // Four base64 images read as text were 2.7 MB in one turn and the server rejected the
        // request at 1.7M tokens. No single message may go in whole past the cap.
        val huge = ChatMessage("tool", JsonPrimitive("x".repeat(AgentHistory.MAX_MESSAGE_CHARS + 5_000)), toolCallId = "c1")
        val capped = AgentHistory.capOversized(listOf(huge)).single()
        assertTrue(capped.textContent.length < huge.textContent.length)
        assertTrue(capped.textContent.contains("truncated"))
        assertEquals("c1", capped.toolCallId)
        // A short message is untouched.
        val small = ChatMessage("user", JsonPrimitive("hello"))
        assertEquals(small, AgentHistory.capOversized(listOf(small)).single())
    }

    @Test fun `a long text part in a vision message is capped, the image stays`() {
        val huge = visionUserMessage("x".repeat(AgentHistory.MAX_MESSAGE_CHARS + 1_000), "abc")
        val capped = AgentHistory.capOversized(listOf(huge)).single()
        assertTrue(capped.textContent.length <= AgentHistory.MAX_MESSAGE_CHARS + 100)
        assertEquals(1, capped.imageCount)
    }

    @Test fun `a context overflow is recognised from the server body`() {
        val real = "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"The input (1771021 tokens) " +
            "exceeds the model's context window of 1048576 tokens. Reduce the length of the input.\"}}"
        assertTrue(AgentHistory.isContextOverflow(real))
        assertTrue(AgentHistory.isContextOverflow("This model's maximum context length is 8192 tokens"))
        assertFalse(AgentHistory.isContextOverflow("tool returned 400 items"))
        assertFalse(AgentHistory.isContextOverflow(null))
    }

    @Test fun `shrinkForContext keeps a little of each tool result and only two images`() {
        val tool = ChatMessage("tool", JsonPrimitive("y".repeat(50_000)), toolCallId = "c1")
        val shots = (1..4).map { shot("shot$it", it.toByte()) }
        val shrunk = AgentHistory.shrinkForContext(listOf(tool) + shots)
        assertTrue(shrunk.first().textContent.length < 5_000)
        assertTrue(shrunk.first().textContent.contains("truncated"))
        assertEquals(2, shrunk.drop(1).count { it.imageCount > 0 })
    }

    @Test fun `four or fewer images pass through untouched`() {
        val history = (1..4).map { shot("shot$it", it.toByte()) }
        assertEquals(history, AgentHistory.cullImages(history))
    }

    @Test fun `only the last four images survive a long visual thread`() {
        val history = (1..6).map { shot("shot$it", it.toByte()) }
        val culled = AgentHistory.cullImages(history)
        assertEquals(6, culled.size)
        // The first two lose their images but keep their text.
        for (i in 0..1) {
            assertEquals(0, culled[i].imageCount)
            assertTrue(culled[i].textContent.startsWith("shot"))
            assertTrue(culled[i].imageUrl() == null)
        }
        // The last four are untouched.
        for (i in 2..5) {
            assertEquals(history[i], culled[i])
            assertEquals(1, culled[i].imageCount)
        }
        assertTrue(culled[0].content.toString().contains("earlier image omitted to save context"))
    }

    @Test fun `one message with many images keeps only the newest parts`() {
        val multi = com.samosaai.masala.llm.attachmentsUserMessage("look", emptyList(), listOf("a", "b", "c", "d", "e", "f"))
        val culled = AgentHistory.cullImages(listOf(ChatMessage("user", JsonPrimitive("first")), multi))
        assertEquals(2, culled.size)
        val kept = culled[1]
        assertEquals(4, kept.imageCount)
        assertTrue(kept.content.toString().contains("2 earlier images omitted"))
        assertTrue(kept.textContent.startsWith("look"))
    }

    @Test fun `messages without images are never touched`() {
        val history = listOf(
            ChatMessage("user", JsonPrimitive("hello")),
            shot("look", 9),
            ChatMessage("assistant", JsonPrimitive("nice")),
        )
        val culled = AgentHistory.cullImages(history)
        assertEquals(history[0], culled[0])
        assertEquals(history[2], culled[2])
        assertFalse(culled[1].imageUrl().isNullOrBlank())
    }

    @Test fun `a custom keep count is honored`() {
        val history = (1..3).map { shot("s$it", it.toByte()) }
        val culled = AgentHistory.cullImages(history, keepParts = 1)
        assertEquals(0, culled[0].imageCount)
        assertEquals(0, culled[1].imageCount)
        assertEquals(1, culled[2].imageCount)
    }
}
