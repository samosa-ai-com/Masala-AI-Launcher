package com.samosaai.masala.agent

import com.samosaai.masala.core.agent.FetchAllowance
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
 * With [fetchOnlyResults] (runs nobody watches), `webfetch` opens only addresses an earlier tool result
 * in this conversation named ([FetchAllowance]).
 *
 * Not safe to run twice at once.
 */
class MasalaAgent(
    private val model: AgentModel,
    private val tools: AgentToolRunner,
    private val definitions: List<ToolDefinition>,
    private val scope: ToolScope,
    private val systemPrompt: (AgentPrompt.PromptContext) -> String,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val maxTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
    private val maxRepeatedToolCalls: Int = DEFAULT_MAX_REPEATED_TOOL_CALLS,
    private val fetchOnlyResults: Boolean = false,
) {
    private var messages: List<ChatMessage> = emptyList()

    /** Older exchanges that no longer go to the model, kept for [transcript]. */
    private var dropped: List<ChatMessage> = emptyList()

    /** The conversation the model sees (the last [KEPT_EXCHANGES] exchanges), without the system prompt. */
    val history: List<ChatMessage> get() = messages

    /**
     * Prompt tokens the server reported for the newest request, when it reports them. The chat
     * uses it to decide when to compact; null until the first reply carries `usage`.
     */
    val lastPromptTokens: Int? get() = model.lastPromptTokens

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
        // The system prompt and the tool schemas ride with every request, so they come out of the
        // same budget; the schema block alone can be tens of thousands of tokens.
        val schemaTokens = AgentHistory.tokensOf(
            listOf(ChatMessage("user", JsonPrimitive(visible.joinToString("\n") { "${it.function.name}${it.function.parameters}" }))),
        )
        // The last tool call and how many times in a row it has repeated, so a loop stops
        // early instead of burning the round budget.
        var lastCall = ""
        var repeats = 0
        try {
            repeat(maxSteps) {
                emit(AgentEvent.Thinking)
                val system = ChatMessage("system", JsonPrimitive(systemPrompt(promptContext())))
                val historyBudget = (maxTokens - schemaTokens - AgentHistory.tokensOf(listOf(system)))
                    .coerceAtLeast(MIN_HISTORY_TOKENS)
                val request = listOf(system) +
                    AgentHistory.cullImages(AgentHistory.trimToTokens(messages, historyBudget)).asSendable()
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
                    val refused = when {
                        !scope.allows(name) -> "Tool '$name' is not available here. Only the listed tools can be used."
                        fetchOnlyResults && name == FetchAllowance.TOOL && !fetchAllowed(args) -> FetchAllowance.REFUSAL
                        else -> null
                    }
                    if (refused != null) {
                        val refusal = ToolResult.Failure(refused)
                        messages = messages + toolMessage(call.id, refusal)
                        emit(AgentEvent.ToolDone(name, refusal))
                        continue
                    }
                    val signature = "$name${call.function.arguments}"
                    repeats = if (signature == lastCall) repeats + 1 else 1
                    lastCall = signature
                    if (repeats > maxRepeatedToolCalls) {
                        messages = AgentHistory.repairOrphanedToolCalls(messages)
                        return@flow emit(AgentEvent.Failed("Stopped: $name was called with the same arguments $repeats times in a row."))
                    }
                    emit(AgentEvent.ToolCall(name, args))
                    val result = tools.run(name, args)
                    messages = messages + toolMessage(call.id, result)
                    emit(AgentEvent.ToolDone(name, result))
                    if (result is ToolResult.Success) result.results().takeIf { it.isNotEmpty() }?.let { emit(AgentEvent.Results(it)) }
                    if (result is ToolResult.NeedsCapability) {
                        // A missing permission is one failed step, not the end of the turn: the
                        // host shows the grant, and the model finishes around it instead of
                        // leaving the user with an error and no answer.
                        emit(AgentEvent.NeedsCapability(result.capability, result.reason))
                    }
                }
            }
            emit(AgentEvent.StepLimit)
        } catch (e: CancellationException) {
            messages = AgentHistory.repairOrphanedToolCalls(messages)
            throw e
        }
    }

    /**
     * What the prompt lists this turn: the latest user text (wildcard skills gate on it) and the
     * skills already started or consulted this conversation (the prompt skips those).
     */
    private fun promptContext(): AgentPrompt.PromptContext = AgentPrompt.PromptContext(
        lastUserText = messages.lastOrNull { it.role == "user" }?.textContent,
        fetchedSkillIds = fetchedSkillIds(),
    )

    private fun fetchedSkillIds(): Set<String> {
        // A refused or failed call answers nothing, so the skill stays listed (tool results that
        // begin with "Error:" or "Not run:" are failures; successes carry the raw output).
        val failedCalls = messages
            .filter { it.role == "tool" && (it.textContent.startsWith("Error:") || it.textContent.startsWith("Not run:")) }
            .mapNotNullTo(HashSet()) { it.toolCallId }
        val ids = HashSet<String>()
        for (message in messages) {
            for (call in message.toolCalls.orEmpty()) {
                if (call.function.name != "run_skill" && call.function.name != "consult_expert") continue
                if (call.id in failedCalls) continue
                runCatching { Json.parseToJsonElement(call.function.arguments) as? JsonObject }
                    .getOrNull()?.get("skill_id")?.let { (it as? JsonPrimitive)?.content }?.let(ids::add)
            }
        }
        return ids
    }

    /**
     * Whether `webfetch` may open the URL in [args]: it must be in a search result of this conversation.
     * The whole [transcript], since [messages] drops exchanges older than [KEPT_EXCHANGES].
     */
    private fun fetchAllowed(args: JsonObject): Boolean {
        val url = (args["url"] as? JsonPrimitive)?.content ?: return false
        val all = transcript
        val searchCalls = all.flatMap { it.toolCalls.orEmpty() }.filter { it.function.name in FetchAllowance.SOURCES }.mapTo(HashSet()) { it.id }
        return FetchAllowance.allows(url, all.filter { it.role == "tool" && it.toolCallId in searchCalls }.map { it.textContent })
    }

    /**
     * Guarantees the request carries something the server will accept: at least one `user` or
     * `tool` message. A window can in principle end up with assistant turns only (an empty trim,
     * a tool-call block), and servers answer such a request with a bare HTTP 400 that repeats on
     * every retry. The note is the same one [AgentHistory] already uses after a trim.
     */
    private fun List<ChatMessage>.asSendable(): List<ChatMessage> =
        if (any { it.role == "user" || it.role == "tool" }) this
        else listOf(ChatMessage("user", JsonPrimitive(AgentHistory.CONTEXT_NOTE))) + this

    private fun toolMessage(callId: String, result: ToolResult): ChatMessage {
        val text = when (result) {
            // Tell the model plainly to stop trying and say what is missing, as a sub-agent's
            // parent expects one clear report rather than a retry loop.
            is ToolResult.NeedsCapability ->
                "Not run: ${result.reason} This needs ${result.capability} from the user, which is not granted. " +
                    "Do not call it again. Finish your answer and say what they have to grant."
            else -> result.asText()
        }
        return ChatMessage("tool", JsonPrimitive(text), toolCallId = callId)
    }

    companion object {
        /** A request that needs many steps (search, read, edit, verify) must not stop early. */
        const val DEFAULT_MAX_STEPS = 100

        /**
         * The context budget one request may use, in tokens. Generous because the window is what
         * keeps a long thread coherent; [AgentHistory.trimToTokens] still bounds every request.
         */
        const val DEFAULT_MAX_CONTEXT_TOKENS = 256_000

        /** Compaction fires here, as a share of [DEFAULT_MAX_CONTEXT_TOKENS], before the trim is needed. */
        const val COMPACT_AT_FRACTION = 0.8

        /** Even a huge system prompt and tool block leave this much room for the conversation. */
        const val MIN_HISTORY_TOKENS = 4_000

        /**
         * The same tool with the same arguments this many times in a row means a loop: stop
         * rather than spend the whole round budget on it.
         */
        const val DEFAULT_MAX_REPEATED_TOOL_CALLS = 4
        const val KEPT_EXCHANGES = 5

        fun ToolResult.asText() = when (this) {
            is ToolResult.Success -> output
            is ToolResult.Failure -> "Error: $message"
            is ToolResult.NeedsCapability -> "Not run: $reason"
        }
    }
}
