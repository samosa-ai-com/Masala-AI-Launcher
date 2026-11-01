package com.samosaai.masala.widgets

import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.layout.ChatCaller
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.ResponseFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sentence → spec without a model: the checker, the repair turn and the probe. */
class WidgetGeneratorTest {

    private class FakeCaller(private val replies: MutableList<Any>) : ChatCaller {
        var calls = 0
        override suspend fun call(messages: List<ChatMessage>, responseFormat: ResponseFormat?, maxTokens: Int, temperature: Float?): String {
            calls++
            return when (val next = replies.removeFirst()) {
                is String -> next
                is Exception -> throw next
                else -> error("bad reply")
            }
        }
    }

    private val good = """{"id":"water","title":"Water","components":[{"type":"counter","key":"glasses","label":"Glasses","goal":8}],"size":{"min":1,"default":1,"max":2}}"""
    private val bad = """{"id":"Bad Id!","title":"x","components":[],"size":{"min":1,"default":1,"max":2}}"""

    private fun request() = WidgetGenerator.Request(userText = "a water counter", homeSummary = "Page 'home': grid(2 items)")

    @Test
    fun cleanSpecGeneratesAtOnce() = runBlocking {
        val gen = WidgetGenerator(FakeCaller(mutableListOf(good)))
        val result = gen.generate(request())
        assertTrue(result is WidgetGenerator.Result.Generated)
        assertEquals("water", (result as WidgetGenerator.Result.Generated).spec.id)
    }

    @Test
    fun badSpecGetsOneRepairTurn() = runBlocking {
        val caller = FakeCaller(mutableListOf(bad, good))
        val result = WidgetGenerator(caller).generate(request())
        assertTrue(result is WidgetGenerator.Result.Generated)
        assertEquals(2, caller.calls)
    }

    @Test
    fun twoBadSpecsReject() = runBlocking {
        val caller = FakeCaller(mutableListOf(bad, bad))
        val result = WidgetGenerator(caller).generate(request())
        assertTrue(result is WidgetGenerator.Result.Rejected)
        assertEquals(2, caller.calls)
        assertTrue((result as WidgetGenerator.Result.Rejected).problems.isNotEmpty())
    }

    @Test
    fun transportTroubleFails() = runBlocking {
        val result = WidgetGenerator(FakeCaller(mutableListOf(RuntimeException("down")))).generate(request())
        assertTrue(result is WidgetGenerator.Result.Failed)
    }

    @Test
    fun revisionKeepsItsId() = runBlocking {
        val current = WidgetSpec(id = "water", title = "Water", components = emptyList())
        val renamed = """{"id":"juice","title":"Water","components":[{"type":"counter","key":"glasses","label":"Glasses"}],"size":{"min":1,"default":1,"max":2}}"""
        val caller = FakeCaller(mutableListOf(renamed, renamed))
        val result = WidgetGenerator(caller).generate(request().copy(revise = current))
        assertTrue(result is WidgetGenerator.Result.Rejected)
        assertTrue((result as WidgetGenerator.Result.Rejected).problems.any { it.message.contains("keeps the id") })
    }

    @Test
    fun probeFailuresReject() = runBlocking {
        val gen = WidgetGenerator(FakeCaller(mutableListOf(good)))
        val result = gen.generate(request()) { listOf(com.samosaai.masala.core.layout.LayoutChecker.Problem("$.x", "probe says no")) }
        assertTrue(result is WidgetGenerator.Result.Rejected)
    }

    @Test
    fun schemaHasEveryW1Component() {
        val text = WidgetSchema.schema().toString()
        listOf("counter", "checklist", "timer", "progress", "chart", "button", "image", "list", "gauge", "row", "column", "text").forEach {
            assertTrue("schema names $it", text.contains("\"$it\""))
        }
        assertTrue("no video yet", !text.contains("\"video\""))
    }
}
