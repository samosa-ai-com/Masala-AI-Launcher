package com.samosaai.masala.agent

import com.samosaai.masala.core.agent.ToolScope
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.withValidToolCallArguments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Runs one tool; the app passes `MasalaToolExecutor::execute`, which gates and logs it. */
fun interface AgentToolRunner {
    suspend fun run(name: String, args: JsonObject): ToolResult
}

/**
 * The agent loop: system prompt + history + the user's message go to the model; each tool call it
 * makes is checked against [scope], run through [tools] (gate and action log are in there), and the
 * result goes back, until the model answers, [maxSteps] model turns pass, or a tool needs a
 * capability. One instance is one conversation; [history] carries follow-ups.
 *
 * Not safe to run twice at once.
 */
class MasalaAgent(
    private val model: AgentModel,
    private val tools: AgentToolRunner,
    private val definitions: List<ToolDefinition>,
    private val scope: ToolScope,
    private val systemPrompt: () -> String,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val maxHistoryChars: Int = DEFAULT_MAX_HISTORY_CHARS,
) {
    private var messages: List<ChatMessage> = emptyList()

    /** Older exchanges that no longer go to the model, kept for [transcript]. */
    private var dropped: List<ChatMessage> = emptyList()

    /** The conversation the model sees (the last [KEPT_EXCHANGES] exchanges), without the system prompt. */
    val history: List<ChatMessage> get() = messages

    /** The whole conversation, including exchanges [history] has let go of: what a skill run saves. */
    val transcript: List<ChatMessage> get() = dropped + messages

    /** Replaces the conversation, e.g. when a skill run is reopened. */
    fun restore(history: List<ChatMessage>) {
        dropped = emptyList()
        messages = AgentHistory.repairOrphanedToolCalls(history)
    }

    fun run(user: ChatMessage): Flow<AgentEvent> = flow {
        val kept = AgentHistory.lastExchanges(messages, KEPT_EXCHANGES - 1)
        dropped = dropped + messages.subList(0, messages.size - kept.size)
        messages = kept + user
        val visible = definitions.filter { scope.allows(it.name) }.map { it.toLlm() }
        try {
            repeat(maxSteps) {
                emit(AgentEvent.Thinking)
                val request = listOf(ChatMessage("system", JsonPrimitive(systemPrompt()))) + AgentHistory.trim(messages, maxHistoryChars)
                val reply = try {
                    model.next(request, visible).withValidToolCallArguments()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@flow emit(AgentEvent.Failed(e.message ?: e::class.java.simpleName))
                }
                messages = messages + reply
                val calls = reply.toolCalls.orEmpty()
                if (calls.isEmpty()) return@flow emit(AgentEvent.Done(reply.textContent))
                if (reply.hasText) emit(AgentEvent.Text(reply.textContent))
                for (call in calls) {
                    val name = call.function.name
                    val args = runCatching { Json.parseToJsonElement(call.function.arguments) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
                    if (!scope.allows(name)) {
                        val refusal = ToolResult.Failure("Tool '$name' is not available here. Only the listed tools can be used.")
                        messages = messages + toolMessage(call.id, refusal)
                        emit(AgentEvent.ToolDone(name, refusal))
                        continue
                    }
                    emit(AgentEvent.ToolCall(name, args))
                    val result = tools.run(name, args)
                    messages = messages + toolMessage(call.id, result)
                    emit(AgentEvent.ToolDone(name, result))
                    if (result is ToolResult.Success && result.files.isNotEmpty()) emit(AgentEvent.Files(result.files))
                    if (result is ToolResult.NeedsCapability) {
                        messages = AgentHistory.repairOrphanedToolCalls(messages)
                        return@flow emit(AgentEvent.NeedsCapability(result.capability, result.reason))
                    }
                }
            }
            emit(AgentEvent.StepLimit)
        } catch (e: CancellationException) {
            messages = AgentHistory.repairOrphanedToolCalls(messages)
            throw e
        }
    }

    private fun toolMessage(callId: String, result: ToolResult) =
        ChatMessage("tool", JsonPrimitive(result.asText()), toolCallId = callId)

    companion object {
        const val DEFAULT_MAX_STEPS = 6
        const val SKILL_MAX_STEPS = 12
        const val DEFAULT_MAX_HISTORY_CHARS = 40_000
        const val KEPT_EXCHANGES = 5

        fun ToolResult.asText() = when (this) {
            is ToolResult.Success -> output
            is ToolResult.Failure -> "Error: $message"
            is ToolResult.NeedsCapability -> "Not run: $reason"
        }
    }
}
