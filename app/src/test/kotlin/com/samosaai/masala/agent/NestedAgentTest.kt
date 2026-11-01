package com.samosaai.masala.agent

import com.samosaai.masala.core.agent.ToolInfo
import com.samosaai.masala.core.agent.ToolScope
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.FunctionCall
import com.samosaai.masala.llm.ToolCall
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shape a consulted expert runs in: a tool of the main agent starts a second agent, waits for
 * its answer and hands it back. Proves the two loops nest and that an expert can be restored with
 * its own earlier exchange, which is what a recall needs.
 */
class NestedAgentTest {
    private fun user(text: String) = ChatMessage("user", JsonPrimitive(text))

    private fun call(id: String, name: String, args: String) = ToolCall(id, function = FunctionCall(name, args))

    private fun assistant(text: String = "", vararg calls: ToolCall) =
        ChatMessage("assistant", JsonPrimitive(text), toolCalls = calls.toList().ifEmpty { null })

    private class Script(private val replies: ArrayDeque<ChatMessage>) : AgentModel {
        val requests = mutableListOf<List<ChatMessage>>()
        override suspend fun next(messages: List<ChatMessage>, tools: List<com.samosaai.masala.llm.ToolDefinition>): ChatMessage {
            requests += messages
            return replies.removeFirst()
        }
    }

    private fun script(vararg replies: ChatMessage) = Script(ArrayDeque(replies.toList()))

    private val consultDefinitions = listOf(ToolDefinition("consult_expert", "", JsonObject(emptyMap())))

    private fun mainAgent(model: AgentModel, runner: AgentToolRunner) = MasalaAgent(
        model = model,
        tools = runner,
        definitions = consultDefinitions,
        scope = ToolScope.command(listOf(ToolInfo("consult_expert", ToolSensitivity.READ_ONLY))),
        systemPrompt = { "You are Masala." },
        maxSteps = 6,
    )

    @Test
    fun aToolRunsASecondAgentAndReturnsItsAnswer() = runBlocking {
        val expertScript = script(assistant("Three days of headache is worth checking."))
        val expert = MasalaAgent(
            model = expertScript,
            tools = AgentToolRunner { _, _ -> ToolResult.Failure("The doctor uses no tools here.") },
            definitions = emptyList(),
            scope = ToolScope.command(emptyList()),
            systemPrompt = { "You are a doctor. Ask what would change your answer." },
            maxSteps = 6,
        )

        val mainScript = script(
            assistant("", call("1", "consult_expert", """{"skill_id":"doctor","question":"headache for 3 days"}""")),
            assistant("The doctor says it is worth checking."),
        )

        var consulted = false
        val events = mainAgent(mainScript, AgentToolRunner { name, _ ->
            assertEquals("consult_expert", name)
            consulted = true
            val answer = expert.run(user("headache for 3 days"))
                .filterIsInstance<AgentEvent.Done>().single().text
            ToolResult.Success(answer)
        }).run(user("I've had a headache for 3 days")).toList()

        assertEquals("The doctor says it is worth checking.", events.filterIsInstance<AgentEvent.Done>().single().text)
        assertTrue(consulted)
        // The expert saw its own instructions, not the main agent's conversation.
        val expertFirstRequest = expertScript.requests.first()
        assertTrue(expertFirstRequest.first().textContent.contains("You are a doctor"))
        assertFalse(expertFirstRequest.any { it.textContent.contains("I've had a headache") })
    }

    @Test
    fun aRestoredExpertRemembersItsEarlierExchange() = runBlocking {
        val expertScript = script(assistant("Ask about the pain."), assistant("Then it is a different picture."))
        val expert = MasalaAgent(
            model = expertScript,
            tools = AgentToolRunner { _, _ -> ToolResult.Failure("none") },
            definitions = emptyList(),
            scope = ToolScope.command(emptyList()),
            systemPrompt = { "You are a doctor." },
            maxSteps = 6,
        )

        expert.run(user("headache for 3 days")).toList()
        val saved = expert.history

        val recalled = MasalaAgent(
            model = expertScript,
            tools = AgentToolRunner { _, _ -> ToolResult.Failure("none") },
            definitions = emptyList(),
            scope = ToolScope.command(emptyList()),
            systemPrompt = { "You are a doctor." },
            maxSteps = 6,
        )
        recalled.restore(saved)
        recalled.run(user("it is behind one eye")).toList()

        // The second conversation carries the first question, which is what makes recall useful.
        val secondRequest = expertScript.requests.last().map { it.textContent }
        assertTrue(secondRequest.contains("headache for 3 days"))
        assertTrue(secondRequest.contains("it is behind one eye"))
    }
}
