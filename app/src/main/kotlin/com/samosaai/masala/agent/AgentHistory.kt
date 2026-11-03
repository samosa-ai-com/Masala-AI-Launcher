package com.samosaai.masala.agent

import com.samosaai.masala.llm.ChatMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Conversation-history repairs that keep a request acceptable to strict OpenAI-compatible servers:
 * history that begins with a tool result or an assistant message, or an assistant message whose
 * `tool_calls` lack results, is rejected with a 400 that would break every later turn.
 */
object AgentHistory {
    const val CANCELLED = "Cancelled: the run was interrupted before this tool completed."
    const val CONTEXT_NOTE = "[System Note: Conversation context restored from previous turn]"

    /** Characters per token the local estimate assumes. */
    const val CHARS_PER_TOKEN = 4

    /**
     * What one image part is charged in the estimate. A ~1k-pixel image costs roughly this many
     * tokens whatever its file size, so base64 length is deliberately not counted.
     */
    const val IMAGE_PART_TOKENS = 1_100

    /** The last [count] exchanges (a user message and everything after it), for follow-ups. */
    fun lastExchanges(history: List<ChatMessage>, count: Int): List<ChatMessage> {
        var seen = 0
        for (i in history.indices.reversed()) {
            if (history[i].role == "user" && !history[i].textContent.startsWith("[") && ++seen == count) return history.subList(i, history.size)
        }
        return history
    }

    /**
     * Rough token count for a message list: four characters per token, the usual rule of thumb.
     * Counts what actually goes on the wire, not just the visible text: tool-call names and
     * arguments are sent too, and each image part is charged the token cost of a ~1k-pixel image
     * rather than its base64 length (a data URI is tens of thousands of characters but a few
     * hundred tokens of model input, and counting the characters would make the window useless).
     */
    fun tokensOf(messages: List<ChatMessage>): Int =
        messages.sumOf { message ->
            val chars = message.textContent.length +
                message.toolCalls.orEmpty().sumOf { it.function.name.length + it.function.arguments.length }
            chars / CHARS_PER_TOKEN + message.imageCount * IMAGE_PART_TOKENS
        }

    /** [trim] on a token budget rather than a character one. */
    fun trimToTokens(history: List<ChatMessage>, maxTokens: Int): List<ChatMessage> =
        trim(history, maxTokens.coerceAtLeast(1) * CHARS_PER_TOKEN)

    /**
     * Drops the oldest messages until the text fits [maxChars], without leaving a tool result at the
     * start, and puts a note first if an assistant message would be.
     *
     * Never returns an empty list. A tail of big tool results can use up the whole budget, which
     * would leave nothing but the system prompt in the request — servers reject that outright
     * (`messages` needs a user or tool role) and the rejection repeats on every retry. In that case
     * the window backs up to the newest message that isn't a tool result, so the turn that owns
     * those results goes along with them.
     */
    fun trim(history: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
        if (history.isEmpty()) return history
        var chars = 0
        var start = history.size
        while (start > 0) {
            chars += history[start - 1].textContent.length
            if (chars > maxChars && start < history.size) break
            start--
        }
        while (start < history.size && history[start].role == "tool") start++
        // The budget ran out inside a block of tool results: keep the turn that called them
        // instead of sending a request with no user or tool message in it.
        if (start >= history.size) {
            val owner = history.indexOfLast { it.role != "tool" }
            start = if (owner >= 0) owner else history.size - 1
        }
        val trimmed = history.subList(start, history.size)
        if (trimmed.firstOrNull()?.role == "assistant") {
            return listOf(ChatMessage("user", JsonPrimitive(CONTEXT_NOTE))) + trimmed
        }
        return trimmed
    }

    /**
     * Strips images from the request down to the newest [keepParts] image parts, so a long visual
     * thread doesn't pay for every screenshot on every turn. Text parts are untouched, and a message
     * that loses parts keeps one note saying how many went. Only the request list is culled, never
     * the stored history.
     */
    fun cullImages(history: List<ChatMessage>, keepParts: Int = 4): List<ChatMessage> {
        val withImages = history.mapIndexed { i, m -> i to imagePartIndices(m) }.filter { it.second.isNotEmpty() }
        if (withImages.sumOf { it.second.size } <= keepParts) return history
        // The newest parts survive; everything older is stripped.
        val keep = HashSet<Pair<Int, Int>>()
        var budget = keepParts
        for ((i, parts) in withImages.asReversed()) {
            for (p in parts.asReversed()) {
                if (budget == 0) break
                keep += i to p
                budget--
            }
            if (budget == 0) break
        }
        return history.mapIndexed { i, message ->
            val parts = message.content as? JsonArray ?: return@mapIndexed message
            val stripped = parts.indices.filter { isImagePart(parts[it]) && (i to it) !in keep }
            if (stripped.isEmpty()) {
                message
            } else {
                val first = stripped.first()
                val note = if (stripped.size == 1) "[earlier image omitted to save context]"
                else "[${stripped.size} earlier images omitted to save context]"
                message.copy(content = buildJsonArray {
                    parts.forEachIndexed { idx, part ->
                        if (idx == first) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", note)
                            })
                        }
                        if (idx !in stripped) add(part)
                    }
                })
            }
        }
    }

    private fun imagePartIndices(message: ChatMessage): List<Int> {
        val parts = message.content as? JsonArray ?: return emptyList()
        return parts.indices.filter { isImagePart(parts[it]) }
    }

    private fun isImagePart(part: JsonElement): Boolean =
        (part as? JsonObject)?.get("type")?.jsonPrimitive?.content == "image_url"

    /** Adds a stub result for every tool call of an assistant message that has none (an interrupted run). */    fun repairOrphanedToolCalls(history: List<ChatMessage>): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(history.size)
        var i = 0
        while (i < history.size) {
            val message = history[i]
            out += message
            i++
            val calls = message.toolCalls.orEmpty()
            if (message.role != "assistant" || calls.isEmpty()) continue
            val answered = HashSet<String>()
            while (i < history.size && history[i].role == "tool") {
                history[i].toolCallId?.let(answered::add)
                out += history[i]
                i++
            }
            for (call in calls) {
                if (call.id !in answered) out += ChatMessage("tool", JsonPrimitive(CANCELLED), toolCallId = call.id)
            }
        }
        return out
    }
}
