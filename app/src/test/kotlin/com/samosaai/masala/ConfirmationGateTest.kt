package com.samosaai.masala

import com.samosaai.masala.actions.ConfirmationGate
import com.samosaai.masala.actions.ConfirmationGate.Decision
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfirmationGateTest {
    private val log = mutableListOf<Triple<String, String, ToolResult>>()
    private fun gate(timeoutMs: Long = 2_000) = ConfirmationGate({ s, m, r -> log += Triple(s, m, r) }, timeoutMs)

    private fun tool(sensitivity: ToolSensitivity) = object : Tool {
        override val definition = ToolDefinition("set_alarm", "", JsonObject(emptyMap()))
        override val sensitivity = sensitivity
        override suspend fun execute(args: JsonObject) = ToolResult.Success("ran")
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.answerWhenAsked(gate: ConfirmationGate, allowed: Boolean) = async {
        while (gate.pending.value == null) delay(5)
        gate.answer(allowed)
    }

    @Test
    fun `allow lets the call through and is logged`() = runBlocking<Unit> {
        val gate = gate()
        answerWhenAsked(gate, true)
        assertEquals(Decision.ALLOWED, gate.confirm("Turn on Focus", subject = "set_mode"))
        assertEquals("confirm:set_mode", log.single().first)
        assertIs<ToolResult.Success>(log.single().third)
        assertNull(gate.pending.value)
    }

    @Test
    fun `cancel and a timeout both stop the action and are logged`() = runBlocking<Unit> {
        val gate = gate(timeoutMs = 80)
        answerWhenAsked(gate, false)
        assertEquals(Decision.DECLINED, gate.confirm("a"))
        assertEquals(Decision.TIMED_OUT, gate.confirm("b"))
        assertEquals(listOf("declined", "timed out"), log.map { (it.third as ToolResult.Failure).message })
    }

    @Test
    fun `as the executor's gate it returns null to allow and a failure otherwise`() = runBlocking<Unit> {
        val gate = gate()
        val args = JsonObject(mapOf("hour" to JsonPrimitive(7)))
        answerWhenAsked(gate, true)
        assertNull(gate.check(tool(ToolSensitivity.CHANGES_SOMETHING), args))
        answerWhenAsked(gate, false)
        assertEquals("The user declined.", (gate.check(tool(ToolSensitivity.CHANGES_SOMETHING), args) as ToolResult.Failure).message)
        assertTrue(log.all { it.first == "confirm:set_alarm" })
    }

    @Test
    fun `questions are asked one at a time`() = runBlocking<Unit> {
        val gate = gate()
        val first = async { gate.confirm("first") }
        val second = async { gate.confirm("second") }
        while (gate.pending.value == null) delay(5)
        assertEquals("first", gate.pending.value?.summary)
        gate.answer(true)
        while (gate.pending.value?.summary != "second") delay(5)
        gate.answer(false)
        assertEquals(Decision.ALLOWED, first.await())
        assertEquals(Decision.DECLINED, second.await())
    }

    @Test
    fun `a destructive tool is asked as destructive`() = runBlocking<Unit> {
        val gate = gate()
        val check = async { gate.check(tool(ToolSensitivity.DESTRUCTIVE), JsonObject(emptyMap())) }
        while (gate.pending.value == null) delay(5)
        assertTrue(gate.pending.value!!.destructive)
        gate.answer(false)
        check.await()
    }
}
