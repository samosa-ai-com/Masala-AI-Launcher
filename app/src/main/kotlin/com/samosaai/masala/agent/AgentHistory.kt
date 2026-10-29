package com.samosaai.masala.agent

import com.samosaai.masala.llm.ChatMessage
import kotlinx.serialization.json.JsonPrimitive

/**
 * Conversation-history repairs that keep a request acceptable to strict OpenAI-compatible servers:
 * history that begins with a tool result or an assistant message, or an assistant message whose
 * `tool_calls` lack results, is rejected with a 400 that would break every later turn.
 */
object AgentHistory {
    const val CANCELLED = "Cancelled: the run was interrupted before this tool completed."
    const val CONTEXT_NOTE = "[System Note: Conversation context restored from previous turn]"

    /** The last [count] exchanges (a user message and everything after it), for follow-ups. */
    fun lastExchanges(history: List<ChatMessage>, count: Int): List<ChatMessage> {
        var seen = 0
        for (i in history.indices.reversed()) {
            if (history[i].role == "user" && !history[i].textContent.startsWith("[") && ++seen == count) return history.subList(i, history.size)
        }
        return history
    }

    /**
     * Drops the oldest messages until the text fits [maxChars], without leaving a tool result at the
     * start, and puts a note first if an assistant message would be.
     */
    fun trim(history: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
        var chars = 0
        var start = history.size
        while (start > 0) {
            chars += history[start - 1].textContent.length
            if (chars > maxChars && start < history.size) break
            start--
        }
        while (start < history.size && history[start].role == "tool") start++
        val trimmed = history.subList(start, history.size)
        if (trimmed.firstOrNull()?.role == "assistant") {
            return listOf(ChatMessage("user", JsonPrimitive(CONTEXT_NOTE))) + trimmed
        }
        return trimmed
    }

    /** Adds a stub result for every tool call of an assistant message that has none (an interrupted run). */
    fun repairOrphanedToolCalls(history: List<ChatMessage>): List<ChatMessage> {
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
