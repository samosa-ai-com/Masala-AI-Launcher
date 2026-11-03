package com.samosaai.masala.core.meeting

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A template a meeting note can be written in. The prompt lives in the app; the shape lives here. */
@Serializable
data class MeetingTemplate(
    val id: String,
    val name: String,
    /** One line the picker shows. */
    val description: String,
    /** Sections this template asks for, in order; the general set is always allowed. */
    val sections: List<String> = DEFAULT_SECTIONS,
) {
    companion object {
        val DEFAULT_SECTIONS = listOf("summary", "key_points", "decisions", "actions", "questions", "follow_ups")

        val General = MeetingTemplate(
            id = "general",
            name = "General",
            description = "Summary, key points, decisions, action items and open questions.",
        )
        val OneOnOne = MeetingTemplate(
            id = "one_on_one",
            name = "1:1",
            description = "Wins, concerns, feedback and what each person will do next.",
            sections = listOf("summary", "key_points", "decisions", "actions", "questions", "follow_ups"),
        )
        val Standup = MeetingTemplate(
            id = "standup",
            name = "Standup",
            description = "What moved, what is blocked, and who is doing what today.",
            sections = listOf("summary", "key_points", "actions", "questions"),
        )

        val all: List<MeetingTemplate> = listOf(General, OneOnOne, Standup)

        fun byId(id: String?): MeetingTemplate = all.firstOrNull { it.id == id } ?: General
    }
}

/** One action item the note extracted: what, who, when, and where in the transcript it came from. */
@Serializable
data class MeetingAction(
    val text: String,
    val owner: String? = null,
    val due: String? = null,
    /** Start of the turn that supports it, so the row can jump to the transcript. */
    @SerialName("at_ms") val atMs: Long? = null,
)

/**
 * The structured note. The model fills the lists through JSON; Markdown is rendered here so every
 * note looks the same and the checkboxes stay ours.
 */
@Serializable
data class MeetingNote(
    val title: String = "",
    val summary: List<String> = emptyList(),
    val keyPoints: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val actions: List<MeetingAction> = emptyList(),
    val questions: List<String> = emptyList(),
    val followUps: List<String> = emptyList(),
    val template: String = MeetingTemplate.General.id,
    /** Windows the writer could not read, as "mm:ss–mm:ss"; listed in the note, never hidden. */
    val failedWindows: List<String> = emptyList(),
    val windows: Int = 0,
    val generatedAt: Long = 0,
) {
    val isEmpty: Boolean
        get() = summary.isEmpty() && keyPoints.isEmpty() && decisions.isEmpty() &&
            actions.isEmpty() && questions.isEmpty() && followUps.isEmpty()

    fun toMarkdown(): String = buildString {
        appendLine("# ${title.ifBlank { "Meeting notes" }}")
        appendLine()
        if (summary.isNotEmpty()) {
            appendLine("## Summary")
            summary.forEach { appendLine("- $it") }
            appendLine()
        }
        if (keyPoints.isNotEmpty()) {
            appendLine("## Key points")
            keyPoints.forEach { appendLine("- $it") }
            appendLine()
        }
        if (decisions.isNotEmpty()) {
            appendLine("## Decisions")
            decisions.forEach { appendLine("- $it") }
            appendLine()
        }
        if (actions.isNotEmpty()) {
            appendLine("## Action items")
            actions.forEach { action ->
                val owner = action.owner?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                val due = action.due?.takeIf { it.isNotBlank() }?.let { " (by $it)" } ?: ""
                appendLine("- [ ] ${action.text}$owner$due")
            }
            appendLine()
        }
        if (questions.isNotEmpty()) {
            appendLine("## Open questions")
            questions.forEach { appendLine("- $it") }
            appendLine()
        }
        if (followUps.isNotEmpty()) {
            appendLine("## Follow-ups")
            followUps.forEach { appendLine("- $it") }
            appendLine()
        }
        if (failedWindows.isNotEmpty()) {
            appendLine("## Transcript gaps")
            appendLine("These parts could not be summarised: ${failedWindows.joinToString(", ")}. The transcript is still complete.")
            appendLine()
        }
    }.trimEnd() + "\n"
}
