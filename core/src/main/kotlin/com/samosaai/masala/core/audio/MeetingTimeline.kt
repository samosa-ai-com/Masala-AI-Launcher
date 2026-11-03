package com.samosaai.masala.core.audio

/**
 * Moves a transcription window onto the meeting's clock. A window is uploaded with the previous
 * chunk's tail in front (the overlap that anchors speaker labels), so a turn at 20 s inside the
 * upload that follows a 12 s overlap and a chunk starting at 5 minutes belongs at 5:08 on the
 * meeting clock — and the turns that only exist in the overlap are dropped.
 */
object MeetingTimeline {

    fun place(turns: List<SpeakerTurn>, chunkStartMs: Long, overlapMs: Long): List<SpeakerTurn> {
        if (turns.isEmpty()) return emptyList()
        val shift = chunkStartMs - overlapMs.coerceAtLeast(0)
        return turns
            .filter { it.endMs > overlapMs }
            .map { turn ->
                val start = (turn.startMs + shift).coerceAtLeast(chunkStartMs)
                val end = (turn.endMs + shift).coerceAtLeast(start)
                turn.copy(startMs = start, endMs = end)
            }
    }
}
