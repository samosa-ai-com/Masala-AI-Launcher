package com.samosaai.masala.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoToolsTest {
    private fun args(vararg items: Triple<String, String, String?>): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            put(
                "items",
                JsonArray(items.map { (content, status, priority) ->
                    buildJsonObject {
                        put("content", JsonPrimitive(content))
                        put("status", JsonPrimitive(status))
                        priority?.let { put("priority", JsonPrimitive(it)) }
                    }
                }),
            )
        }

    @Test fun `a fresh list renders with marks`() {
        val list = TodoTools.TodoList()
        val result = list.handle("todowrite", args(Triple("Book flights", "in_progress", "high"), Triple("Pack", "pending", null)))
        assertTrue(result.success, result.message)
        assertTrue("[… in_progress] Book flights (high)" in result.message, result.message)
        assertTrue("[○ pending] Pack (medium)" in result.message, result.message)
    }

    @Test fun `replacing the list forgets the old one`() {
        val list = TodoTools.TodoList()
        list.handle("todowrite", args(Triple("Old", "pending", null)))
        val result = list.handle("todowrite", args(Triple("New", "completed", "low")))
        assertTrue(result.success)
        assertTrue("Old" !in result.message)
        assertTrue("[✓ completed] New (low)" in result.message)
    }

    @Test fun `bad items fail with reasons`() {
        val list = TodoTools.TodoList()
        assertFalse(list.handle("todowrite", args(Triple("", "pending", null))).success)
        assertTrue(list.handle("todowrite", args(Triple("X", "doing", null))).message.contains("pending"))
        val many = Array(31) { Triple("t$it", "pending", null) }
        assertTrue(list.handle("todowrite", args(*many)).message.contains("30"))
        assertFalse(list.handle("nope", args()).success)
    }

    @Test fun `long content is capped`() {
        val list = TodoTools.TodoList()
        val result = list.handle("todowrite", args(Triple("x".repeat(500), "pending", null)))
        assertTrue(result.success)
        assertTrue("x".repeat(500) !in result.message)
    }
}
