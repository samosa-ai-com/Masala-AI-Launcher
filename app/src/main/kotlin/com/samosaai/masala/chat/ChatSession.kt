package com.samosaai.masala.chat

import kotlinx.serialization.Serializable

/**
 * Who said it. Errors are stored so a failed turn is visible when the session is reopened;
 * [SYSTEM] marks a note the app wrote, such as a history summary.
 */
@Serializable
enum class TurnRole { USER, MASALA, ERROR, SYSTEM }

/** An expert a reply consulted, named so the thread can credit it after a reload. */
@Serializable
data class ExpertCredit(val id: String, val title: String)

/**
 * One thing on the conversation screen. [steps] are the tool steps a reply took, kept
 * beside the text so a reopened session shows the same trace; [experts] are the personas
 * whose answers shaped it; [at] is when it landed.
 */
@Serializable
data class ChatTurn(
    val role: TurnRole,
    val text: String,
    val steps: List<String> = emptyList(),
    val at: Long = 0L,
    val experts: List<ExpertCredit> = emptyList(),
)

/**
 * One conversation: everything said in it, in order. [title] is taken from the first
 * user turn (editable later), and [updatedAt] sorts the session list.
 */
@Serializable
data class ChatSession(
    val id: String,
    val title: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val turns: List<ChatTurn> = emptyList(),
) {
    /** The last line of the conversation, for the session list. */
    val preview: String
        get() = turns.lastOrNull { it.text.isNotBlank() }?.text?.replace('\n', ' ')?.take(80).orEmpty()
}

/**
 * What the session list needs, without loading every turn. [search] is the title plus the
 * conversation's text (bounded), so the list can be filtered without touching the disk.
 */
data class ChatSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
    val turns: Int,
    val search: String = "",
)
