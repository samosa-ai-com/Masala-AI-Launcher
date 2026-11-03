package com.samosaai.masala.core.audio

import kotlinx.serialization.Serializable

/**
 * One turn of a speaker-labelled transcript: what was said, when it started and ended, and who said
 * it. [speaker] is the endpoint's label for the request (`A`, `B`, …); it is only meaningful inside
 * one request, so a meeting stitches these across windows before showing them.
 */
@Serializable
data class SpeakerTurn(
    val startMs: Long,
    val endMs: Long,
    val speaker: String? = null,
    val text: String,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/**
 * A whole audio file's turns. [durationMs] is the endpoint's own `audioDurationMs` when it sent one,
 * otherwise the last turn's end.
 */
@Serializable
data class DiarizedTranscript(
    val turns: List<SpeakerTurn>,
    val durationMs: Long = 0,
) {
    /** Speaker labels in the order they first appear, nulls dropped. */
    val speakers: List<String>
        get() = turns.mapNotNull { it.speaker }.distinct()

    /** The turns with every gap filled: a turn without a label takes the previous one, or the next. */
    fun withInheritedSpeakers(): DiarizedTranscript {
        if (turns.none { it.speaker.isNullOrBlank() }) return this
        val first = turns.firstOrNull { !it.speaker.isNullOrBlank() }?.speaker
        var current = first
        val filled = turns.map { turn ->
            if (!turn.speaker.isNullOrBlank()) {
                current = turn.speaker
                turn
            } else {
                turn.copy(speaker = current)
            }
        }
        return copy(turns = filled)
    }

    /** The same turns, moved later on the timeline (a window that starts after the file's first second). */
    fun shifted(byMs: Long): DiarizedTranscript =
        copy(turns = turns.map { it.copy(startMs = it.startMs + byMs, endMs = it.endMs + byMs) })

    /** Drops the turns that belong to an overlap with a previous window. */
    fun withoutOverlap(overlapMs: Long): DiarizedTranscript =
        copy(turns = turns.filter { it.endMs > overlapMs })

    /** `mm:ss` (or `h:mm:ss` past an hour), the form the transcript and prompts use. */
    fun clock(ms: Long): String = clockOf(ms)

    /** Plain text, one turn per line with its time and speaker: what the notes model reads. */
    fun numbered(): String = turns.joinToString("\n") { turn ->
        "[${clockOf(turn.startMs)}] ${turn.speaker ?: "?"}: ${turn.text.trim()}"
    }

    /** Markdown for a reader: a bullet per turn, speaker and time in bold. */
    fun toMarkdown(): String = turns.joinToString("\n") { turn ->
        val who = turn.speaker?.takeIf { it.isNotBlank() } ?: "Speaker"
        "- **${clockOf(turn.startMs)} · $who:** ${turn.text.trim()}"
    }

    /** Everything said, without speakers or times. */
    fun plainText(): String = turns.joinToString(" ") { it.text.trim() }.trim()

    companion object {
        fun clockOf(ms: Long): String {
            val total = (ms.coerceAtLeast(0) / 1000).toInt()
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            val seconds = total % 60
            return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
            else "%d:%02d".format(minutes, seconds)
        }
    }
}
