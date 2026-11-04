package com.samosaai.masala.meetings

import android.content.Context
import com.samosaai.masala.core.audio.DiarizedTranscript
import com.samosaai.masala.core.audio.SpeakerTurn
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.meeting.ChunkStatus
import com.samosaai.masala.core.meeting.MeetingBookmark
import com.samosaai.masala.core.meeting.MeetingNote
import com.samosaai.masala.core.meeting.MeetingStatus
import com.samosaai.masala.core.meeting.MeetingSummary
import com.samosaai.masala.data.MasalaDatabase
import com.samosaai.masala.data.MeetingChunkEntity
import com.samosaai.masala.data.MeetingSessionEntity
import com.samosaai.masala.data.MeetingTurnEntity
import com.samosaai.masala.data.SpeakerTalk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Every meeting the app knows about: the session row, its 5-minute chunks and the speaker-labelled
 * turns. Audio stays in app-private files under
 * `files/meetings/<id>/`; deleting a meeting deletes the folder with it.
 *
 * All writes are suspend and off the main thread; the screens read flows.
 */
class MeetingRepository(
    private val context: Context,
    private val db: MasalaDatabase,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions = db.meetings()
    private val chunks = db.meetingChunks()
    private val turns = db.meetingTurns()

    /** The live session, or null. One at a time: the microphone is one. */
    val active: StateFlow<MeetingSessionEntity?> = sessions.activeFlow()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** The newest meeting, for the widget and the list header when nothing is live. */
    val latest: StateFlow<MeetingSessionEntity?> = sessions.latestFlow()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val all: Flow<List<MeetingSessionEntity>> = sessions.all()

    /** Recent meetings with the numbers the list needs, newest first. */
    val summaries: Flow<List<MeetingSummary>> = sessions.all().map { rows ->
        rows.map { row ->
            val all = turns.byMeeting(row.id)
            MeetingSummary(
                id = row.id,
                title = row.title,
                startedAt = row.startedAt,
                durationMs = row.activeMs,
                status = row.status,
                speakers = all.mapNotNull { it.speaker }.distinct().size,
                oneLine = oneLineOf(row),
                hasAudio = chunks.byMeeting(row.id).any { File(it.path).isFile },
            )
        }
    }.flowOn(Dispatchers.IO)

    fun session(id: String): Flow<MeetingSessionEntity?> = sessions.flow(id)
    fun turnsOf(id: String): Flow<List<MeetingTurnEntity>> = turns.flow(id)
    fun chunksOf(id: String): Flow<List<MeetingChunkEntity>> = chunks.flow(id)

    suspend fun get(id: String): MeetingSessionEntity? = sessions.get(id)
    suspend fun activeNow(): MeetingSessionEntity? = sessions.active()
    suspend fun latest(limit: Int = 5): List<MeetingSessionEntity> = sessions.latest(limit)
    suspend fun turnsNow(id: String): List<MeetingTurnEntity> = turns.byMeeting(id)
    suspend fun chunksNow(id: String): List<MeetingChunkEntity> = chunks.byMeeting(id)
    suspend fun talkTime(id: String): List<SpeakerTalk> = turns.talkTime(id)

    suspend fun create(title: String, template: String, language: String): MeetingSessionEntity {
        val now = clock()
        val session = MeetingSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { defaultTitle(now) },
            startedAt = now,
            status = MeetingStatus.RECORDING,
            template = template,
            language = language,
            createdAt = now,
            updatedAt = now,
        )
        sessions.upsert(session)
        dir(session.id).mkdirs()
        return session
    }

    suspend fun rename(id: String, title: String) = sessions.rename(id, title.trim().ifBlank { "Meeting" }, clock())

    suspend fun setStatus(id: String, status: String) = sessions.setStatus(id, status, clock())

    suspend fun heartbeat(id: String, activeMs: Long) = sessions.setActiveMs(id, activeMs, clock())

    suspend fun finish(id: String, status: String, activeMs: Long) =
        sessions.finish(id, status, activeMs, clock(), clock())

    suspend fun setError(id: String, error: String?) = sessions.setError(id, error, clock())

    /** Adds a chunk the recorder has just opened. It stays [ChunkStatus.OPEN] until it is closed. */
    suspend fun addChunk(meetingId: String, idx: Int, path: File, startMs: Long, status: String = ChunkStatus.OPEN) {
        chunks.upsert(
            MeetingChunkEntity(
                id = chunkId(meetingId, idx),
                meetingId = meetingId,
                idx = idx,
                path = path.absolutePath,
                startMs = startMs,
                durationMs = 0,
                status = status,
            ),
        )
    }

    /** The recorder closed this file: from here on the transcriber may take it. */
    suspend fun closeChunk(meetingId: String, idx: Int, durationMs: Long) =
        // One write: status and duration land together, and closing never spends a retry.
        chunks.close(meetingId, idx, durationMs)

    suspend fun setChunkDuration(meetingId: String, idx: Int, durationMs: Long) =
        chunks.setDuration(chunkId(meetingId, idx), durationMs)

    suspend fun setChunkStatus(meetingId: String, idx: Int, status: String, error: String? = null) =
        chunks.setStatus(chunkId(meetingId, idx), status, error)

    suspend fun finishChunk(meetingId: String, idx: Int, uncertain: Boolean, error: String? = null) =
        chunks.finish(chunkId(meetingId, idx), ChunkStatus.DONE, uncertain, error)

    suspend fun failChunk(meetingId: String, idx: Int, error: String) =
        chunks.finish(chunkId(meetingId, idx), ChunkStatus.FAILED, false, error)

    suspend fun nextChunk(meetingId: String, maxAttempts: Int = 3): MeetingChunkEntity? =
        chunks.nextPending(meetingId, maxAttempts)

    /** Puts chunks a killed worker left mid-flight back in the queue. */
    suspend fun resetStuckChunks(meetingId: String) = chunks.resetStuck(meetingId)

    /** The meeting screen's Retry: failed chunks are queued again from attempt zero. */
    suspend fun retryFailedChunks(meetingId: String) = chunks.retryFailed(meetingId)

    /** The audio files of a meeting, for the "delete after notes" preference. */
    suspend fun deleteChunkFiles(meetingId: String) = withContext(Dispatchers.IO) {
        chunks.byMeeting(meetingId).forEach { runCatching { File(it.path).delete() } }
    }

    suspend fun chunk(meetingId: String, idx: Int): MeetingChunkEntity? = chunks.byIndex(meetingId, idx)

    suspend fun remainingChunks(meetingId: String): Int = chunks.remaining(meetingId)
    suspend fun chunkCount(meetingId: String): Int = chunks.count(meetingId)

    /** Replaces a chunk's turns in one go, so re-running a chunk is idempotent. */
    suspend fun replaceTurns(meetingId: String, chunkIdx: Int, list: List<SpeakerTurn>, shiftMs: Long) =
        withContext(Dispatchers.IO) {
            turns.deleteByChunk(meetingId, chunkIdx)
            if (list.isEmpty()) return@withContext
            turns.insertAll(
                list.map { turn ->
                    MeetingTurnEntity(
                        meetingId = meetingId,
                        chunkIdx = chunkIdx,
                        startMs = (turn.startMs + shiftMs).coerceAtLeast(0),
                        endMs = (turn.endMs + shiftMs).coerceAtLeast(turn.startMs + shiftMs),
                        speaker = turn.speaker,
                        text = turn.text.trim(),
                    )
                },
            )
            refreshSpeakerCount(meetingId)
        }

    suspend fun deleteTurn(meetingId: String, turnId: Long) {
        turns.deleteById(turnId)
        refreshSpeakerCount(meetingId)
    }

    /** Recomputes the session's detected-speaker count after turns change. */
    suspend fun refreshSpeakerCount(meetingId: String) {
        val count = turns.speakerCount(meetingId)
        sessions.setSpeakerCount(meetingId, count, clock())
    }

    suspend fun setNote(id: String, note: MeetingNote, status: String) {
        val json = MasalaJson.strict.encodeToString(MeetingNote.serializer(), note)
        sessions.setNote(id, note.toMarkdown(), json, status, null, clock())
    }

    suspend fun setNoteFailure(id: String, error: String) =
        sessions.setNote(id, null, null, MeetingStatus.READY, error, clock())

    suspend fun setSpeakerNames(id: String, names: Map<String, String>) =
        sessions.setSpeakerNames(
            id,
            MasalaJson.strict.encodeToString(MapSerializer(String.serializer(), String.serializer()), names),
            clock(),
        )

    suspend fun setDoneActions(id: String, done: Set<Int>) =
        sessions.setDoneActions(
            id,
            MasalaJson.strict.encodeToString(ListSerializer(Int.serializer()), done.sorted()),
            clock(),
        )

    suspend fun setRawNotes(id: String, notes: String, bookmarks: List<MeetingBookmark>) =
        sessions.setNotes(
            id,
            notes,
            MasalaJson.strict.encodeToString(ListSerializer(MeetingBookmark.serializer()), bookmarks),
            clock(),
        )

    suspend fun setBrainPath(id: String, path: String?) = sessions.setBrainPath(id, path, clock())

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        MeetingWork.cancel(context, id)
        turns.deleteByMeeting(id)
        chunks.deleteByMeeting(id)
        sessions.delete(id)
        dir(id).deleteRecursively()
        // Upload and overlap copies live in the cache, outside the meeting folder.
        File(context.cacheDir, "meeting-$id").deleteRecursively()
        context.cacheDir.listFiles { f -> f.name.startsWith("meeting-prev-$id-") }?.forEach { runCatching { it.delete() } }
    }

    suspend fun deleteAudio(id: String) = withContext(Dispatchers.IO) {
        dir(id).deleteRecursively()
        dir(id).mkdirs()
    }

    /** A session left recording or paused by a killed process: keep the files, stop pretending. */
    suspend fun recoverStale(now: Long = clock(), olderThanMs: Long = ORPHAN_MS) {
        val cutoff = now - olderThanMs
        sessions.stale(cutoff).forEach { row ->
            sessions.setStatus(row.id, MeetingStatus.INTERRUPTED, now)
            // The chunk that was still being written has no intact file; the closed ones do.
            chunks.abandonOpen(row.id, "The recording stopped before this part was finished.")
            MeetingWork.enqueue(context, row.id)
        }
        // A session can also be left "transcribing" with a chunk the recorder never closed (a kill
        // between stop and the close write). Those rows are terminal and the queue can finish.
        chunks.meetingsWithOpenChunks().forEach { id ->
            chunks.abandonOpen(id, "The recording stopped before this part was finished.")
            MeetingWork.enqueue(context, id)
        }
    }

    fun dir(meetingId: String): File = File(context.filesDir, "meetings/$meetingId")

    fun chunkFile(meetingId: String, idx: Int): File = File(dir(meetingId), "chunk-%03d.m4a".format(idx))

    fun chunkId(meetingId: String, idx: Int): String = "$meetingId-$idx"

    fun speakerNames(row: MeetingSessionEntity): Map<String, String> = runCatching {
        MasalaJson.lenient.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()),
            row.speakerNames,
        )
    }.getOrDefault(emptyMap())

    fun doneActions(row: MeetingSessionEntity): Set<Int> = runCatching {
        MasalaJson.lenient.decodeFromString(ListSerializer(Int.serializer()), row.doneActions).toSet()
    }.getOrDefault(emptySet())

    fun bookmarks(row: MeetingSessionEntity): List<MeetingBookmark> = runCatching {
        MasalaJson.lenient.decodeFromString(ListSerializer(MeetingBookmark.serializer()), row.bookmarks)
    }.getOrDefault(emptyList())

    fun note(row: MeetingSessionEntity): MeetingNote? = row.noteJson?.let { json ->
        runCatching { MasalaJson.lenient.decodeFromString(MeetingNote.serializer(), json) }.getOrNull()
    }

    /** The display name for a detected label: the user's name for it, else "Speaker A". */
    fun displayName(label: String?, names: Map<String, String>): String {
        if (label.isNullOrBlank()) return "Speaker"
        return names[label]?.takeIf { it.isNotBlank() } ?: "Speaker $label"
    }

    private fun oneLineOf(row: MeetingSessionEntity): String {
        val note = runCatching { row.noteJson?.let { MasalaJson.lenient.decodeFromString(MeetingNote.serializer(), it) } }.getOrNull()
        return note?.summary?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: note?.keyPoints?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: when (row.status) {
                MeetingStatus.RECORDING -> "Recording…"
                MeetingStatus.PAUSED -> "Paused"
                MeetingStatus.TRANSCRIBING -> "Transcribing…"
                MeetingStatus.INTERRUPTED -> "Interrupted — the recording stopped early"
                MeetingStatus.FAILED -> "Something went wrong"
                else -> "No notes yet"
            }
    }

    private fun defaultTitle(now: Long): String {
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(now))
        return "Meeting · $date"
    }

    companion object {
        /** A recording whose heartbeat stopped this long ago is from a dead process. */
        const val ORPHAN_MS = 120_000L
    }
}
