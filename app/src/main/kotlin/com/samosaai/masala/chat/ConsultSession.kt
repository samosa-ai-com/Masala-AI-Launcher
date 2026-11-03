package com.samosaai.masala.chat

import kotlinx.serialization.Serializable

/**
 * One line of a consultation, in the expert's own record. The expert sees its answered exchanges
 * when it is consulted again in the same chat; the user only sees the credited answer in the thread.
 * [TurnRole.ERROR] records a question that never got an answer, so the record stays honest without
 * pretending the expert said it.
 */
@Serializable
data class ConsultTurn(
    val role: TurnRole,
    val text: String,
    val at: Long = 0L,
)

/**
 * One expert's record inside one chat: [id] is `<chatId>-c<n>`, so a consult always belongs to
 * the conversation that caused it. Not a chat: it is never listed, titled or searched, and it is
 * deleted with its chat. [title] is the skill's title when it was first consulted.
 */
@Serializable
data class ConsultSession(
    val id: String,
    val chatId: String,
    val skillId: String,
    val title: String = "",
    val turns: List<ConsultTurn> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** Answered exchanges, which is what "exchange 2 of 10" counts. */
    val exchanges: Int get() = pairs().size

    /**
     * Questions asked, answered or not. The cap uses this: a model that keeps failing must stop
     * too, not only one that keeps getting answers.
     */
    val questions: Int get() = turns.count { it.role == TurnRole.USER }

    /** The question and answer pairs so far, oldest first. A failed question is not a pair. */
    fun pairs(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var question: String? = null
        for (turn in turns) {
            when (turn.role) {
                TurnRole.USER -> question = turn.text
                TurnRole.MASALA -> {
                    question?.let { out += it to turn.text }
                    question = null
                }
                else -> Unit
            }
        }
        return out
    }
}
