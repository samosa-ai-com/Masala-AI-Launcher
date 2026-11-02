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
import kotlinx.coroutines.withContext
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
    fun `a tool-provided summary replaces the generic one`() = runBlocking<Unit> {
        val gate = gate()
        val args = JsonObject(mapOf("id" to JsonPrimitive("price")))
        val tool = com.samosaai.masala.core.tool.FunctionTool(
            ToolDefinition("place_widget", "", JsonObject(emptyMap())),
            ToolSensitivity.CHANGES_SOMETHING,
            confirmSummary = { "Put \"price\" on home (fetches api.example.com every 15s)" },
        ) { com.samosaai.masala.core.tool.ActionResult.ok("") }
        answerWhenAsked(gate, true)
        assertNull(gate.check(tool, args))
        assertEquals("Put \"price\" on home (fetches api.example.com every 15s)", log.last().second)
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
    fun `allow all lets a changing tool through without asking and logs it`() = runBlocking<Unit> {
        val gate = gate()
        val result = withContext(ConfirmationGate.AllowAll { true }) {
            gate.check(tool(ToolSensitivity.CHANGES_SOMETHING), JsonObject(emptyMap()))
        }
        assertNull(result)
        assertNull(gate.pending.value)
        assertEquals("confirm:set_alarm", log.single().first)
        assertEquals("allowed (allow all)", (log.single().third as ToolResult.Success).output)
    }

    @Test
    fun `allow all still asks before anything destructive`() = runBlocking<Unit> {
        val gate = gate()
        val check = async(ConfirmationGate.AllowAll { true }) {
            gate.check(tool(ToolSensitivity.DESTRUCTIVE), JsonObject(emptyMap()))
        }
        while (gate.pending.value == null) delay(5)
        assertTrue(gate.pending.value!!.destructive)
        gate.answer(false)
        assertEquals("The user declined.", (check.await() as ToolResult.Failure).message)
    }

    @Test
    fun `allow all switched off asks as usual`() = runBlocking<Unit> {
        val gate = gate()
        val check = async(ConfirmationGate.AllowAll { false }) { gate.confirm("Turn on Focus") }
        while (gate.pending.value == null) delay(5)
        gate.answer(true)
        assertEquals(Decision.ALLOWED, check.await())
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

    @Test
    fun `making an app is confirmed in words, not as a wall of HTML`() {
        val html = "<html>" + "x".repeat(20_000) + "</html>"
        val summary = ConfirmationGate.summarize(
            "create_mini_app",
            JsonObject(mapOf("title" to JsonPrimitive("Snake"), "html" to JsonPrimitive(html))),
        )
        assertEquals("Add \"Snake\" to your home screen as an app", summary)
        // Changing one is a short sentence too.
        assertEquals("Change that app", ConfirmationGate.summarize("update_mini_app", JsonObject(mapOf("id" to JsonPrimitive("a1")))))
        // A model that omits the title still gets a sentence, never the HTML.
        assertEquals(
            "Add a new app to your home screen",
            ConfirmationGate.summarize("create_mini_app", JsonObject(mapOf("html" to JsonPrimitive(html)))),
        )
    }

    @Test
    fun `widget placement is confirmed in words, not as spec JSON`() {
        assertEquals(
            "Put \"water\" on home",
            ConfirmationGate.summarize("place_widget", JsonObject(mapOf("id" to JsonPrimitive("water")))),
        )
        assertEquals(
            "Take \"water\" off home",
            ConfirmationGate.summarize("remove_widget", JsonObject(mapOf("id" to JsonPrimitive("water")))),
        )
        assertEquals(
            "Delete the \"water\" widget everywhere",
            ConfirmationGate.summarize("delete_widget", JsonObject(mapOf("id" to JsonPrimitive("water")))),
        )
        assertEquals(
            "Add the \"Garage\" camera",
            ConfirmationGate.summarize("add_stream_source", JsonObject(mapOf("name" to JsonPrimitive("Garage")))),
        )
    }

    @Test
    fun `a long argument is still cut in the summary so the sheet stays usable`() {
        val layout = "{\"pages\":" + "x".repeat(20_000) + "}"
        val summary = ConfirmationGate.summarize("edit_layout", JsonObject(mapOf("label" to JsonPrimitive("Tidy"), "layout_json" to JsonPrimitive(layout))))
        assertTrue(summary.length < 400, summary)
        assertTrue(summary.contains("characters)"), summary)
    }
}
