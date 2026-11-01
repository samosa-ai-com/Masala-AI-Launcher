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
