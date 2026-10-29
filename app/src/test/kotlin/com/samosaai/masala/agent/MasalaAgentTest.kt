package com.samosaai.masala.agent

import com.samosaai.masala.core.agent.ToolInfo
import com.samosaai.masala.core.agent.ToolScope
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.FunctionCall
import com.samosaai.masala.llm.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MasalaAgentTest {
    private val definitions = listOf("get_time", "set_alarm", "dial").map { ToolDefinition(it, "", JsonObject(emptyMap())) }
    private val infos = listOf(
        ToolInfo("get_time", ToolSensitivity.READ_ONLY),
        ToolInfo("set_alarm", ToolSensitivity.CHANGES_SOMETHING),
        ToolInfo("dial", ToolSensitivity.READ_ONLY),
    )

    private fun call(id: String, name: String, args: String = "{}") = ToolCall(id, function = FunctionCall(name, args))
    private fun assistant(text: String = "", vararg calls: ToolCall) =
        ChatMessage("assistant", JsonPrimitive(text), toolCalls = calls.toList().ifEmpty { null })
    private fun user(text: String) = ChatMessage("user", JsonPrimitive(text))

    private class Script(private val replies: ArrayDeque<ChatMessage>) : AgentModel {
        val requests = mutableListOf<List<ChatMessage>>()
        val toolsSeen = mutableListOf<List<String>>()
        override suspend fun next(messages: List<ChatMessage>, tools: List<com.samosaai.masala.llm.ToolDefinition>): ChatMessage {
            requests += messages
            toolsSeen += tools.map { it.function.name }
            return replies.removeFirst()
        }
    }

    private fun agent(
        model: AgentModel,
        scope: ToolScope = ToolScope.command(infos),
        maxSteps: Int = 6,
        runner: AgentToolRunner = AgentToolRunner { name, _ -> ToolResult.Success("$name ok") },
    ) = MasalaAgent(model, runner, definitions, scope, { "system" }, maxSteps)

    private fun script(vararg replies: ChatMessage) = Script(ArrayDeque(replies.toList()))

    @Test fun answersWithNoTools() = runBlocking {
        val events = agent(script(assistant("Hello"))).run(user("hi")).toList()
        assertEquals(listOf(AgentEvent.Thinking, AgentEvent.Done("Hello")), events)
    }

    @Test fun oneToolCallThenAnswer() = runBlocking {
        val model = script(assistant("", call("1", "get_time")), assistant("It is noon"))
        val a = agent(model)
        val events = a.run(user("time?")).toList()
        assertTrue(events.any { it is AgentEvent.ToolCall && it.name == "get_time" })
        assertEquals(AgentEvent.Done("It is noon"), events.last())
        assertEquals(listOf("user", "assistant", "tool", "assistant"), a.history.map { it.role })
        assertEquals("get_time ok", a.history[2].textContent)
        assertEquals("system", model.requests[1].first().textContent)
    }

    @Test fun filesAndTypedResultsAreReportedTogether() = runBlocking {
        val app = Produced("mini_app", "Snake", "ab12cd34")
        val runner = AgentToolRunner { _, _ -> ToolResult.Success("made", listOf("/out/song.mp3"), listOf(app)) }
        val events = agent(script(assistant("", call("1", "get_time")), assistant("Done")), runner = runner).run(user("x")).toList()
        assertEquals(
            AgentEvent.Results(listOf(Produced("audio", "song.mp3", "/out/song.mp3"), app)),
            events.filterIsInstance<AgentEvent.Results>().single(),
        )
    }

    @Test fun twoParallelCallsGetTwoResults() = runBlocking {
        val a = agent(script(assistant("", call("1", "get_time"), call("2", "dial")), assistant("done")))
        a.run(user("both")).toList()
        assertEquals(listOf("1", "2"), a.history.filter { it.role == "tool" }.map { it.toolCallId })
    }

    @Test fun outOfScopeCallIsRefusedAndFedBack() = runBlocking {
        val ran = mutableListOf<String>()
        val scope = ToolScope.unattended(ToolScope.command(infos), infos)
        val model = script(assistant("", call("1", "set_alarm")), assistant("Can't"))
        val a = agent(model, scope) { name, _ -> ran += name; ToolResult.Success("ok") }
        val events = a.run(user("alarm")).toList()
        assertEquals(emptyList(), ran)
        val done = events.filterIsInstance<AgentEvent.ToolDone>().single()
        assertIs<ToolResult.Failure>(done.result)
        assertTrue(a.history.single { it.role == "tool" }.textContent.contains("not available"))
        assertEquals(listOf("get_time", "dial"), model.toolsSeen.first())
    }

    @Test fun stepLimit() = runBlocking {
        val model = script(assistant("", call("1", "get_time")), assistant("", call("2", "get_time")), assistant("never"))
        val events = agent(model, maxSteps = 2).run(user("loop")).toList()
        assertEquals(AgentEvent.StepLimit, events.last())
    }

    @Test fun needsCapabilityStopsTheRun() = runBlocking {
        val model = script(assistant("", call("1", "get_time"), call("2", "dial")), assistant("never"))
        val a = agent(model) { _, _ -> ToolResult.NeedsCapability("special:usage_access", "Needs usage access") }
        val events = a.run(user("x")).toList()
        assertEquals(AgentEvent.NeedsCapability("special:usage_access", "Needs usage access"), events.last())
        // Both calls have a result, so the next request is valid.
        assertEquals(listOf("1", "2"), a.history.filter { it.role == "tool" }.map { it.toolCallId })
    }

    @Test fun failedToolIsReportedToTheModel() = runBlocking {
        val a = agent(script(assistant("", call("1", "get_time")), assistant("sorry"))) { _, _ -> ToolResult.Failure("boom") }
        a.run(user("x")).toList()
        assertEquals("Error: boom", a.history.single { it.role == "tool" }.textContent)
    }

    @Test fun modelErrorBecomesFailed() = runBlocking {
        val failing = AgentModel { _, _ -> error("offline") }
        val events = agent(failing).run(user("x")).toList()
        assertEquals(AgentEvent.Failed("offline"), events.last())
    }

    @Test fun cancellationLeavesNoOrphanedToolCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val model = script(assistant("", call("1", "get_time"), call("2", "dial")))
        val a = agent(model) { _, _ -> started.complete(Unit); awaitCancellation() }
        val job = launch { a.run(user("x")).collect {} }
        started.await()
        job.cancel()
        job.join()
        assertEquals(listOf("1", "2"), a.history.filter { it.role == "tool" }.map { it.toolCallId })
        assertTrue(a.history.filter { it.role == "tool" }.all { it.textContent == AgentHistory.CANCELLED })
    }

    @Test fun followUpKeepsHistory() = runBlocking {
        val model = script(assistant("first"), assistant("second"))
        val a = agent(model)
        a.run(user("one")).toList()
        a.run(user("two")).toList()
        assertEquals(listOf("user", "assistant", "user"), model.requests[1].drop(1).map { it.role })
    }

    @Test fun onlyTheLastFiveExchangesSurvive() = runBlocking {
        val model = script(*Array(8) { assistant("a$it") })
        val a = agent(model)
        repeat(8) { a.run(user("q$it")).toList() }
        assertEquals(5, a.history.count { it.role == "user" })
        assertEquals("q3", a.history.first().textContent)
    }

    @Test fun transcriptKeepsWhatTheHistoryDropped() = runBlocking {
        val model = script(*Array(8) { assistant("a$it") })
        val a = agent(model)
        repeat(8) { a.run(user("q$it")).toList() }
        assertEquals((0 until 8).flatMap { listOf("q$it", "a$it") }, a.transcript.map { it.textContent })
        assertEquals(a.history, a.transcript.takeLast(a.history.size))
    }
}

class AgentHistoryTest {
    private fun user(t: String) = ChatMessage("user", JsonPrimitive(t))
    private fun assistant(t: String, vararg ids: String) =
        ChatMessage("assistant", JsonPrimitive(t), toolCalls = ids.map { ToolCall(it, function = FunctionCall("x", "{}")) }.ifEmpty { null })
    private fun tool(id: String) = ChatMessage("tool", JsonPrimitive("r"), toolCallId = id)

    @Test fun repairAddsOnlyMissingResults() {
        val repaired = AgentHistory.repairOrphanedToolCalls(listOf(user("u"), assistant("", "1", "2", "3"), tool("2"), user("next")))
        assertEquals(listOf("user", "assistant", "tool", "tool", "tool", "user"), repaired.map { it.role })
        assertEquals(setOf("1", "2", "3"), repaired.filter { it.role == "tool" }.map { it.toolCallId }.toSet())
    }

    @Test fun repairLeavesCompleteHistoryAlone() {
        val h = listOf(user("u"), assistant("", "1"), tool("1"), assistant("done"))
        assertEquals(h, AgentHistory.repairOrphanedToolCalls(h))
    }

    @Test fun trimDropsOldestAndNeverStartsOnToolResult() {
        val h = listOf(user("a".repeat(100)), assistant("", "1"), tool("1"), assistant("b".repeat(10)), user("c"), assistant("d"))
        val trimmed = AgentHistory.trim(h, maxChars = 14)
        assertEquals("user", trimmed.first().role)
        assertEquals(h.takeLast(2), trimmed.takeLast(2))
    }

    @Test fun trimPrependsNoteWhenAssistantWouldBeFirst() {
        val h = listOf(user("a".repeat(100)), assistant("answer"))
        val trimmed = AgentHistory.trim(h, maxChars = 10)
        assertEquals(AgentHistory.CONTEXT_NOTE, trimmed.first().textContent)
        assertEquals("assistant", trimmed[1].role)
    }

    @Test fun trimKeepsAtLeastTheNewestMessage() {
        val h = listOf(user("x".repeat(500)))
        assertEquals(h, AgentHistory.trim(h, maxChars = 10))
    }
}
