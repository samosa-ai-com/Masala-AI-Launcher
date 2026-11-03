package com.samosaai.masala.meetings

import android.content.Context
import android.content.Intent
import com.samosaai.masala.core.audio.DiarizedTranscript
import com.samosaai.masala.core.meeting.MeetingNote
import com.samosaai.masala.data.MeetingSessionEntity
import com.samosaai.masala.secondbrain.SecondBrainRepository
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Takes a finished meeting out of the app (plan §4.5): a Second Brain note, a share sheet, or a chat
 * prompt that turns one action item into a reminder. The Brain file carries `type: meeting` so the
 * vault's own health check and the agent's search treat it as a first-class record.
 */
object MeetingExport {

    /** The vault path for a meeting note; stable, so saving again updates the same file. */
    fun brainPath(meeting: MeetingSessionEntity): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(meeting.startedAt))
        // The short id keeps two same-titled meetings on one day from overwriting each other.
        return "notes/$date-${slug(meeting.title)}-${meeting.id.take(8)}.md"
    }

    fun markdown(
        meeting: MeetingSessionEntity,
        note: MeetingNote?,
        names: Map<String, String>,
        turns: List<com.samosaai.masala.data.MeetingTurnEntity>,
        transcript: Boolean = true,
    ): String = buildString {
        // Only the vault's allowed frontmatter keys: anything else fails its record check. The
        // meeting's own details belong in the body.
        appendLine("---")
        appendLine("title: ${yaml(meeting.title)}")
        appendLine("area: Personal")
        appendLine("type: meeting")
        appendLine("updated: ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}")
        appendLine("source: Masala meeting notes")
        note?.summary?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { appendLine("summary: ${yaml(it)}") }
        appendLine("---")
        appendLine()
        appendLine("**Date:** ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(meeting.startedAt))} · " +
            "**Duration:** ${meeting.activeMs / 60_000} min · " +
            "**Speakers:** ${turns.mapNotNull { it.speaker }.distinct().joinToString(", ") { names[it] ?: "Speaker $it" }}")
        appendLine()
        appendLine("_Meeting id: ${meeting.id}_")
        appendLine()
        appendLine(note?.toMarkdown()?.removePrefix("# ${note.title}")?.trimStart() ?: meeting.noteMarkdown.orEmpty())
        if (transcript && turns.isNotEmpty()) {
            appendLine()
            appendLine("## Transcript")
            turns.forEach { turn ->
                val who = names[turn.speaker] ?: turn.speaker?.let { "Speaker $it" } ?: "Speaker"
                appendLine("- **${DiarizedTranscript.clockOf(turn.startMs)} · $who:** ${turn.text.trim()}")
            }
        }
    }.trimEnd() + "\n"

    /** Writes (or updates) the note in the vault. Returns the path, or null when the vault refused. */
    suspend fun saveToBrain(
        brain: SecondBrainRepository,
        meeting: MeetingSessionEntity,
        note: MeetingNote?,
        names: Map<String, String>,
        turns: List<com.samosaai.masala.data.MeetingTurnEntity>,
    ): String? {
        val path = brainPath(meeting)
        val body = markdown(meeting, note, names, turns)
        val outcome = runCatching { brain.write(path, body, append = false, allowDuplicate = true) }.getOrNull()
        if (outcome?.ok != true) {
            android.util.Log.w("MeetingExport", "Second Brain refused $path: ${outcome?.errors?.joinToString("; ")}")
        }
        return if (outcome?.ok == true) path else null
    }

    fun share(context: Context, meeting: MeetingSessionEntity, note: MeetingNote?, names: Map<String, String>, turns: List<com.samosaai.masala.data.MeetingTurnEntity>) {
        val text = markdown(meeting, note, names, turns, transcript = false)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, meeting.title)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** What the "Create reminder" chip asks the assistant, which can settle a time with the user. */
    fun reminderPrompt(meeting: MeetingSessionEntity, text: String, owner: String?): String =
        "From the meeting \"${meeting.title}\" (${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(meeting.startedAt))}), " +
            "remind me to: $text" + (owner?.takeIf { it.isNotBlank() }?.let { " (owner $it)" } ?: "") +
            ". Ask me when if I did not say."

    /** A frontmatter scalar that survives colons, quotes and a leading dash. */
    internal fun yaml(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    internal fun slug(title: String): String {
        val cleaned = title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
        return cleaned.ifBlank { "meeting" }
    }
}
