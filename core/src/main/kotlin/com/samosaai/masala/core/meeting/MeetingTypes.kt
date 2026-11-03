package com.samosaai.masala.core.meeting

import kotlinx.serialization.Serializable

/** A moment the user marked while listening. [atMs] is on the meeting clock. */
@Serializable
data class MeetingBookmark(
    val atMs: Long,
    val text: String = "",
)

/** Everything the meeting list shows, without loading turns or notes. */
@Serializable
data class MeetingSummary(
    val id: String,
    val title: String,
    val startedAt: Long,
    val durationMs: Long,
    val status: String,
    val speakers: Int = 0,
    val oneLine: String = "",
    val hasAudio: Boolean = true,
)

/** The session states the app acts on. Anything else is treated as [READY] by the UI. */
object MeetingStatus {
    const val RECORDING = "recording"
    const val PAUSED = "paused"
    const val TRANSCRIBING = "transcribing"
    const val READY = "ready"
    const val FAILED = "failed"
    /** Found unfinished at start: the process died while recording. */
    const val INTERRUPTED = "interrupted"

    val active = setOf(RECORDING, PAUSED)
    val unfinished = setOf(RECORDING, PAUSED, TRANSCRIBING, INTERRUPTED)
}

/** Chunk states for the transcription queue. */
object ChunkStatus {
    /** Still being written by the recorder: never in the transcription queue. */
    const val OPEN = "open"
    const val RECORDED = "recorded"
    const val TRANSCRIBING = "transcribing"
    const val DONE = "done"
    const val FAILED = "failed"
}
