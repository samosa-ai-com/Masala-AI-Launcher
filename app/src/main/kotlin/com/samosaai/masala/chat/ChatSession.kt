package com.samosaai.masala.chat

import kotlinx.serialization.Serializable

/**
 * Who said it. Errors are stored so a failed turn is visible when the session is reopened;
 * [SYSTEM] marks a note the app wrote, such as a history summary. [STATUS] marks what the app
 * noted about one turn — a stop — with the steps it had taken, so the trace survives the reload.
 */
@Serializable
enum class TurnRole { USER, MASALA, ERROR, STATUS, SYSTEM }

/** Something a reply made that the user can open: a mini app, a file, an installed app. */
@Serializable
data class ChatResult(val kind: String, val title: String, val ref: String)

/** An expert a reply consulted, named so the thread can credit it after a reload. */
@Serializable
data class ExpertCredit(val id: String, val title: String)

/**
 * One thing on the conversation screen. [steps] are the tool steps a reply took, kept
 * beside the text so a reopened session shows the same trace; [experts] are the personas
 * whose answers shaped it; [at] is when it landed; [attachmentPaths] are the app-private
 * files the turn's images were written to, so a reopened chat can hand the media tools a
 * path (default keeps every existing row decodable).
 */
@Serializable
data class ChatTurn(
    val role: TurnRole,
    val text: String,
    val steps: List<String> = emptyList(),
    val at: Long = 0L,
    val experts: List<ExpertCredit> = emptyList(),
    /** What the turn made, kept so the card survives a reload. */
    val results: List<ChatResult> = emptyList(),
    /** Absolute paths under `filesDir/chat_attachments` for this turn's images, if still on disk. */
    val attachmentPaths: List<String> = emptyList(),
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
