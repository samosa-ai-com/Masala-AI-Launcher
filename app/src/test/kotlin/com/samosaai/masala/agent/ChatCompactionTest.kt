package com.samosaai.masala.agent

import com.samosaai.masala.actions.ActionDispatcher
import com.samosaai.masala.chat.ChatTurn
import com.samosaai.masala.chat.TurnRole
import com.samosaai.masala.core.agent.ToolInfo
import com.samosaai.masala.core.agent.ToolScope
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.llm.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Compaction keeps the open question, and a long thread never sends a request the server refuses. */
class ChatCompactionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = mutableListOf<List<ChatMessage>>()
    private val summarized = mutableListOf<String>()
    private var summaryAnswer: String? = "- user wanted a face box; open: verify the box"

    private fun controller() = CommandController(
        scope = scope,
        resolveApp = { null },
        names = { emptyList() },
        run = { ActionDispatcher.Outcome.Done() },
        skills = { listOf(MasalaSkill(id = "s", title = "S", instructions = "")) },
        newAgent = {
            MasalaAgent(
                // Records every request the model is handed, then answers in plain text.
                AgentModel { messages, _ -> requests += messages; ChatMessage("assistant", JsonPrimitive("Done.")) },
                { _, _ -> ToolResult.Success("") },
                listOf(ToolDefinition("x", "", JsonObject(emptyMap()))),
                ToolScope.command(listOf(ToolInfo("x", ToolSensitivity.READ_ONLY))),
                { _ -> "system" },
            )
        },
        summarize = { transcript -> summarized += transcript; summaryAnswer },
        // A deliberately small window, so a short test history crosses the compaction line.
        maxContextTokens = 1_000,
    )

    private suspend fun CommandController.settled() =
        withTimeout(15_000) { state.first { it is CommandState.Reply || it is CommandState.Error } }

    /** A stored chat long enough to cross the compaction line, ending in an unanswered question. */
    private fun longChat(): List<ChatTurn> = buildList {
        repeat(12) {
            add(ChatTurn(TurnRole.USER, "q$it " + "x".repeat(8_000)))
            add(ChatTurn(TurnRole.MASALA, "a$it"))
        }
        add(ChatTurn(TurnRole.USER, "and the open question?"))
    }

    @Test fun `compaction summarises the older thread and appends the new question once`() = runBlocking<Unit> {
        val c = controller()
        c.restoreSession(longChat())
        c.ask("a brand new question")
        c.settled()

        assertTrue(summarized.isNotEmpty(), "compaction did not run")
        val request = requests.last()
        // The summary stands in for every older turn.
        assertTrue(request.any { it.textContent == summaryAnswer })
        // The question is there exactly once, and it is last.
        assertEquals(listOf("a brand new question"), request.map { it.textContent }.filter { it == "a brand new question" })
        assertEquals("a brand new question", request.last().textContent)
        // The old thread's turns are gone from the request, not merely buried under a summary.
        assertTrue(request.none { it.textContent.startsWith("q0 ") })
    }

    @Test fun `switching to a short chat does not compact on the old chat's token count`() = runBlocking<Unit> {
        val c = controller()
        c.restoreSession(longChat())
        c.ask("first")
        c.settled()
        val afterFirst = summarized.size

        // A different chat: its budget starts clean, so a two-turn thread must not be summarised.
        c.restoreSession(
            listOf(
                ChatTurn(TurnRole.USER, "hi"),
                ChatTurn(TurnRole.MASALA, "hello"),
            ),
        )
        c.ask("how are you")
        c.settled()
        assertEquals(afterFirst, summarized.size, "a short chat inherited the previous chat's token count")
    }

    @Test fun `compaction failure leaves the thread windowed, never empty`() = runBlocking<Unit> {
        summaryAnswer = null
        val c = controller()
        c.restoreSession(longChat())
        c.ask("and the open question?")
        c.settled()

        assertTrue(requests.all { request -> request.any { it.role == "user" || it.role == "tool" } })
        assertTrue(requests.last().any { it.textContent == "and the open question?" })
    }

    @Test fun `request always carries a user message after the system prompt`() = runBlocking<Unit> {
        val c = controller()
        c.ask("hello")
        assertIs<CommandState.Reply>(c.settled())
        val request = requests.last()
        assertEquals("system", request.first().role)
        assertTrue(request.drop(1).any { it.role == "user" || it.role == "tool" }, "server would 400: ${request.map { it.role }}")
    }

    @Test fun `http failures read as something a person can act on`() {
        assertEquals(
            "The model refused that request (HTTP 400). If this chat is long, start a new one and ask again there.",
            friendlyModelError("HTTP 400"),
        )
        assertEquals(
            "The model server refused the key. Check it in Settings → Models & keys.",
            friendlyModelError("HTTP 401"),
        )
        assertEquals("The model didn't answer. Try again.", friendlyModelError(null))
        assertEquals("socket closed", friendlyModelError("socket closed"))
        // A number inside a tool's own message is not an HTTP status line.
        assertEquals("tool returned 400 items", friendlyModelError("tool returned 400 items"))
        // A number in a tool's own message is not an HTTP status line.
        assertEquals("tool returned 400 items", friendlyModelError("tool returned 400 items"))
    }
}
