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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
        // Images a tool read, handed to the model once the round's tool results are in place.
        val readImages = mutableListOf<ReadImage>()
        try {
            repeat(maxSteps) {
                emit(AgentEvent.Thinking)
                var answer: ChatMessage? = null
                var contextRetried = false
                while (answer == null) {
                    val system = ChatMessage("system", JsonPrimitive(systemPrompt(promptContext())))
                    val historyBudget = (maxTokens - schemaTokens - AgentHistory.tokensOf(listOf(system)))
                        .coerceAtLeast(MIN_HISTORY_TOKENS)
                    val request = listOf(system) +
                        AgentHistory.cullImages(
                            AgentHistory.trimToTokens(AgentHistory.capOversized(messages), historyBudget),
                        ).asSendable()
                    try {
                        answer = model.next(request, visible).withValidToolCallArguments()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One oversized step (a file listing, a base64 image read) used to fail the
                        // whole turn. Keep a little of every tool output, drop the oldest images
                        // and ask once more — the user sees an answer instead of a dead end.
                        if (!contextRetried && AgentHistory.isContextOverflow(e.message)) {
                            contextRetried = true
                            messages = AgentHistory.shrinkForContext(messages)
                            emit(AgentEvent.Thinking)
                            continue
                        }
                        return@flow emit(AgentEvent.Failed(e.message ?: e::class.java.simpleName))
                    }
                }
                val reply = answer
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
                    val output = (result as? ToolResult.Success)?.output.orEmpty()
                    val image = (result as? ToolResult.Success)?.let { readImage(it.output) }
                    when {
                        image != null -> {
                            readImages += image
                            messages = messages + toolMessage(call.id, ToolResult.Success(image.summary))
                        }
                        // Too large to attach: say so. Keeping the base64 would put megabytes of
                        // noise into history and the model still could not see the picture.
                        output.startsWith("IMAGE_DATA:") ->
                            messages = messages + toolMessage(
                                call.id,
                                ToolResult.Success(
                                    "Read an image, but it is too large to attach. Ask for a smaller " +
                                        "image, or downscale it first.",
                                ),
                            )
                        else -> messages = messages + toolMessage(call.id, result)
                    }
                    emit(AgentEvent.ToolDone(name, result))
                    if (result is ToolResult.Success) result.results().takeIf { it.isNotEmpty() }?.let { emit(AgentEvent.Results(it)) }
                    if (result is ToolResult.NeedsCapability) {
                        // A missing permission is one failed step, not the end of the turn: the
                        // host shows the grant, and the model finishes around it instead of
                        // leaving the user with an error and no answer.
                        emit(AgentEvent.NeedsCapability(result.capability, result.reason))
                    }
                }
                // Images read by tools become a vision message *after* the tool results: a user
                // message between an assistant tool_call and its result is rejected by strict
                // servers, and the pixels cost a few hundred tokens instead of megabytes of
                // base64 text (which is what used to blow the window).
                if (readImages.isNotEmpty()) {
                    messages = messages + visionMessage(readImages)
                    readImages.clear()
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
        val raw = when (result) {
            // Tell the model plainly to stop trying and say what is missing, as a sub-agent's
            // parent expects one clear report rather than a retry loop.
            is ToolResult.NeedsCapability ->
                "Not run: ${result.reason} This needs ${result.capability} from the user, which is not granted. " +
                    "Do not call it again. Finish your answer and say what they have to grant."
            is ToolResult.Success -> result.asText() + shownNote(result.results())
            else -> result.asText()
        }
        // The transcript keeps the full output on purpose (a skill's saved run is a record);
        // the request caps each message in AgentHistory.capOversized, so size is paid once, at
        // the wire, not by silently losing data the user may need to reopen.
        return ChatMessage("tool", JsonPrimitive(raw), toolCallId = callId)
    }

    /**
     * The chat already shows a tool's results (images, masks, cut-outs, videos, apps) as cards. Without
     * saying so, a model that got "Overlay saved." went looking for the files and read the pictures back
     * as base64 until the request was too large to send.
     */
    internal fun shownNote(results: List<com.samosaai.masala.core.tool.Produced>): String =
        if (results.isEmpty()) {
            ""
        } else {
            "\nAlready shown to the user as cards: " +
                results.joinToString("; ") { "${it.kind} '${it.title}' (${it.ref})" } +
                ". Don't read or list them to show them again; describe what was found and finish."
        }

    /**
     * An image a tool read. [summary] is the text that goes into the tool message; [data] rides as
     * an image part, which a vision model charges by pixels rather than by base64 length.
     */
    private data class ReadImage(val mime: String, val name: String, val dimensions: String, val data: String) {
        val summary: String get() = "Read image: $name ($dimensions). The image itself is attached."
    }

    /** `IMAGE_DATA:<mime>:<WxH>:<bytes>:<name>:<base64>` from read_file, or null. */
    private fun readImage(output: String): ReadImage? {
        if (!output.startsWith("IMAGE_DATA:")) return null
        val parts = output.split(":", limit = 6)
        if (parts.size < 6) return null
        val base64 = parts[5]
        // A 4 MB image is ~5.3 MB of base64: attached, it is a few thousand tokens, but the
        // request body is not free either. Past this, describe it instead.
        if (base64.length > MAX_INLINE_IMAGE_CHARS) return null
        return ReadImage(parts[1], parts[4], parts[2], base64)
    }

    private fun visionMessage(images: List<ReadImage>): ChatMessage {
        val label = if (images.size == 1) {
            "The assistant read an image file: ${images[0].name} (${images[0].dimensions})."
        } else {
            "The assistant read ${images.size} image files: " +
                images.joinToString { "${it.name} (${it.dimensions})" } + "."
        }
        return ChatMessage("user", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("type", "text")
                put("text", label)
            })
            images.forEach { image ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:${image.mime};base64,${image.data}") }
                })
            }
        })
    }

    companion object {
        /** A request that needs many steps (search, read, edit, verify) must not stop early. */
        const val DEFAULT_MAX_STEPS = 100

        /** Images above this base64 size are described, not attached. */
        const val MAX_INLINE_IMAGE_CHARS = 5_500_000

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
