package com.samosaai.masala.meetings

import android.content.Context
import android.util.Log
import com.samosaai.masala.audio.AndroidTranscriber
import com.samosaai.masala.audio.AudioApi
import com.samosaai.masala.audio.ModelCategory
import com.samosaai.masala.audio.SpeechConfig
import com.samosaai.masala.core.audio.DiarizedTranscript
import com.samosaai.masala.core.audio.MeetingTimeline
import com.samosaai.masala.core.audio.PcmEnhancer
import com.samosaai.masala.core.audio.SpeakerLabels
import com.samosaai.masala.core.audio.SpeakerTurn
import com.samosaai.masala.core.audio.WavPcm
import com.samosaai.masala.core.meeting.MeetingStatus
import com.samosaai.masala.data.MeetingChunkEntity
import com.samosaai.masala.data.MeetingSessionEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns closed recording chunks into speaker-labelled turns (plan §4.3).
 *
 * Per chunk: AAC → 16 kHz mono WAV (the platform decoder), level/rumble clean-up, the previous chunk's
 * tail in front for the speaker-overlap anchor, upload with `DIARIZATION`, map this window's labels
 * onto the meeting's, and store the turns on the meeting clock. Everything is idempotent: a chunk can
 * be transcribed again (the old turns are replaced) without duplicating anything.
 */
class MeetingTranscriber(
    private val context: Context,
    private val repo: MeetingRepository,
    private val settings: MeetingSettings,
    private val speech: () -> SpeechConfig,
    private val clientFactory: (SpeechConfig) -> AudioApi = { config ->
        AudioApi(config.baseUrl, config.apiKey, timeoutSeconds = ASR_TIMEOUT_SECONDS)
    },
) {

    private var cachedClient: Pair<Pair<String, String>, AudioApi>? = null

    /** Transcribes every recorded chunk, in order. False when one failed (the worker retries later). */
    /** False when no transcription endpoint is set: the worker must fail visibly, not retry. */
    fun configured(): Boolean = speech().baseUrl.isNotBlank()

    suspend fun drain(meetingId: String): Boolean = withContext(Dispatchers.IO) {
        repo.resetStuckChunks(meetingId)
        while (true) {
            val chunk = repo.nextChunk(meetingId, MAX_ATTEMPTS) ?: return@withContext true
            val meeting = repo.get(meetingId) ?: return@withContext true
            val result = transcribeChunk(meeting, chunk)
            if (result.isFailure) {
                Log.w(TAG, "Chunk ${chunk.idx} of $meetingId failed", result.exceptionOrNull())
                return@withContext false
            }
        }
        @Suppress("UNREACHABLE_CODE")
        true
    }

    suspend fun transcribeChunk(meeting: MeetingSessionEntity, chunk: MeetingChunkEntity): Result<Unit> =
        withContext(Dispatchers.IO) {
            val config = speech()
            if (config.baseUrl.isBlank()) {
                val message = "No transcription endpoint is configured. Set one in Settings → Models & keys."
                repo.failChunk(meeting.id, chunk.idx, message)
                return@withContext Result.failure(IllegalStateException(message))
            }
            repo.setChunkStatus(meeting.id, chunk.idx, com.samosaai.masala.core.meeting.ChunkStatus.TRANSCRIBING)
            runCatching {
                val source = File(chunk.path)
                if (!source.isFile || source.length() == 0L) error("The recording of chunk ${chunk.idx + 1} is missing")
                val work = workDir(meeting.id)
                work.mkdirs()
                val wavFile = File(work, "chunk-%03d.wav".format(chunk.idx))
                if (!wavFile.isFile || wavFile.length() == 0L) {
                    // An imported chunk is already the mono 16-bit WAV the splitter wrote; only the
                    // recorder's AAC needs the platform decoder.
                    val converted = if (source.extension.equals("wav", ignoreCase = true)) {
                        runCatching { source.copyTo(wavFile, overwrite = true); true }.getOrDefault(false)
                    } else {
                        AndroidTranscriber.toWav(source, wavFile)
                    }
                    if (!converted) error("Couldn't convert chunk ${chunk.idx + 1} to WAV")
                }
                val raw = WavPcm.readOrThrow(wavFile)
                // Level and rumble clean-up once per chunk; re-runs reuse the cleaned file.
                val enhanced = if (raw.samples.isEmpty()) raw else {
                    val cleaned = PcmEnhancer.enhance(raw.samples, raw.sampleRate)
                    WavPcm.Audio(cleaned, raw.sampleRate)
                }
                WavPcm.write(wavFile, enhanced)
                val durationMs = if (chunk.durationMs > 0) chunk.durationMs else enhanced.durationMs

                // The overlap anchor: the previous chunk's tail in front of this window.
                val previous = previousChunk(meeting.id, chunk.idx)
                val previousWav = previous?.let { previousAudio(it) }
                val upload = if (previousWav != null) {
                    WavPcm.withOverlap(previousWav, enhanced, settings.overlapMs)
                } else {
                    enhanced
                }
                val overlapUsedMs = (upload.durationMs - enhanced.durationMs).coerceAtLeast(0)
                val uploadFile = File(work, "upload-%03d.wav".format(chunk.idx))
                WavPcm.write(uploadFile, upload)

                val transcript = upload(uploadFile, config)
                // The meeting may have been deleted while this upload was in flight.
                if (repo.get(meeting.id) == null) return@runCatching
                val mapped = mapLabels(meeting, chunk, previous, transcript, previousWav, overlapUsedMs)
                // The window's own clock is local (0 = the start of the upload, overlap included);
                // place() moves it onto the meeting's clock and drops the overlap.
                val placed = MeetingTimeline.place(mapped.turns, chunk.startMs, overlapUsedMs)
                repo.replaceTurns(meeting.id, chunk.idx, placed, 0)
                trimBoundary(meeting.id, chunk.idx)
                if (chunk.durationMs <= 0) repo.setChunkDuration(meeting.id, chunk.idx, durationMs)
                repo.finishChunk(meeting.id, chunk.idx, uncertain = mapped.uncertain)
                runCatching { uploadFile.delete() }
                // The AAC source stays; these WAVs are only the upload and the overlap anchor, and a
                // three-hour meeting would park ~350 MB of them in the cache.
                runCatching { wavFile.delete() }
                Unit
            }.onFailure { error ->
                val message = error.message?.take(300) ?: error::class.java.simpleName
                // A retry must re-convert from the AAC source: enhancing an already-enhanced WAV
                // applies the high-pass, gate and normalization a second time.
                runCatching { File(workDir(meeting.id), "chunk-%03d.wav".format(chunk.idx)).delete() }
                runCatching { File(workDir(meeting.id), "upload-%03d.wav".format(chunk.idx)).delete() }
                repo.failChunk(meeting.id, chunk.idx, message)
            }
        }

    private suspend fun upload(file: File, config: SpeechConfig): DiarizedTranscript {
        val api = client(config)
        val model = model(config, api)
        // One retry: a gateway timeout and a dropped connection look the same and usually pass.
        var last: Result<DiarizedTranscript> = api.transcribeDiarized(file, model, settings.language.ifBlank { config.sttLanguage })
        if (last.isFailure) {
            Log.w(TAG, "Transcription failed once, retrying", last.exceptionOrNull())
            last = api.transcribeDiarized(file, model, settings.language.ifBlank { config.sttLanguage })
        }
        return last.getOrElse { error ->
            val message = error.message.orEmpty()
            throw IllegalStateException(when {
                message.contains("401") -> "The transcription API rejected the key (HTTP 401)."
                message.contains("404") || message.contains("model_not_found") -> "This endpoint has no diarization model: $message"
                message.contains("exceeds the 10 minute") -> "A chunk was too long for the endpoint: $message"
                else -> message.ifBlank { "Transcription failed" }
            })
        }
    }

    private suspend fun model(config: SpeechConfig, api: AudioApi): String {
        val wanted = settings.model.ifBlank { config.sttModel }.trim()
        if (wanted.isNotEmpty()) return wanted
        return runCatching { api.listAudioModels() }.getOrDefault(emptyList())
            .firstOrNull { it.category == ModelCategory.STT }?.id
            ?: error(
                "The transcription endpoint advertises no speech model. Set one in Settings → " +
                    "Meeting notes → Transcription model.",
            )
    }

    private fun client(config: SpeechConfig): AudioApi = synchronized(this) {
        val key = config.baseUrl to config.apiKey
        cachedClient?.takeIf { it.first == key }?.second
            ?: clientFactory(config).also { cachedClient = key to it }
    }

    /** The previous chunk's audio, for the overlap; converted once per run and cached in the work dir. */
    private fun previousAudio(chunk: MeetingChunkEntity): WavPcm.Audio? {
        // This is the *previous* chunk (idx 0 when transcribing chunk 1), so an `idx <= 0` guard
        // here silently disabled every overlap. previousChunk() already returns null when there is
        // no previous window.
        val source = File(chunk.path)
        if (!source.isFile) return null
        // Kept in the meeting's work dir, which is removed when the meeting finishes or is deleted.
        val file = File(workDir(chunk.meetingId), "prev-%03d.wav".format(chunk.idx))
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            if (!AndroidTranscriber.toWav(source, file)) return null
        }
        return WavPcm.read(file)
    }

    private suspend fun previousChunk(meetingId: String, idx: Int): MeetingChunkEntity? =
        if (idx <= 0) null else repo.chunk(meetingId, idx - 1)

    private data class Mapped(val turns: List<SpeakerTurn>, val uncertain: Boolean)

    private suspend fun mapLabels(
        meeting: MeetingSessionEntity,
        chunk: MeetingChunkEntity,
        previous: MeetingChunkEntity?,
        transcript: DiarizedTranscript,
        previousWav: WavPcm.Audio?,
        overlapUsedMs: Long,
    ): Mapped {
        val incoming = transcript.withInheritedSpeakers()
        if (chunk.idx == 0 || previous == null || overlapUsedMs <= 0) {
            // First window: its labels are the meeting's labels.
            return Mapped(incoming.withoutOverlap(0).turns, uncertain = false)
        }
        val previousTurns = repo.turnsNow(meeting.id)
            .filter { it.chunkIdx == previous.idx }
            .map { SpeakerTurn(it.startMs - chunkStartOf(previous), it.endMs - chunkStartOf(previous), it.speaker, it.text) }
        val previousDuration = previous.durationMs.takeIf { it > 0 } ?: previousWav?.durationMs ?: 0
        val mapping = SpeakerLabels.map(previousTurns, previousDuration, incoming.turns, overlapUsedMs)

        val existing = repo.turnsNow(meeting.id).mapNotNull { it.speaker }.distinct()
        val labels = mapping.labels.toMutableMap()
        val used = (existing + labels.values).toMutableList()
        incoming.speakers.forEach { local ->
            if (local !in labels) {
                val next = SpeakerLabels.nextLabel(used)
                labels[local] = next
                used += next
            }
        }
        val mapped = incoming.withoutOverlap(overlapUsedMs).turns.map { turn ->
            turn.copy(speaker = turn.speaker?.let { labels[it] ?: it })
        }
        return Mapped(mapped, mapping.uncertain)
    }

    /**
     * A window can end mid-sentence while the next one, which starts a few seconds earlier, has the
     * whole sentence. Drop the previous chunk's trailing fragment when the new first turn covers it.
     */
    private suspend fun trimBoundary(meetingId: String, chunkIdx: Int) {
        if (chunkIdx <= 0) return
        val all = repo.turnsNow(meetingId)
        val previous = all.filter { it.chunkIdx == chunkIdx - 1 }.maxByOrNull { it.endMs } ?: return
        val first = all.filter { it.chunkIdx == chunkIdx }.minByOrNull { it.startMs } ?: return
        // Only a turn that ended near this boundary can be the fragment the next window repeated.
        // A phrase minutes before the boundary ("thank you") is real speech, not a duplicate.
        val boundary = repo.chunk(meetingId, chunkIdx)?.startMs ?: return
        if (boundary - previous.endMs > settings.overlapMs) return
        // Only the same person's fragment, and only when the new turn contains it word for word in
        // order. A shared half of a sentence ("we should") is not a duplicate of "we should launch".
        if (previous.speaker != first.speaker) return
        val old = previous.text.lowercase().replace(Regex("[^\\p{L}\\p{N}\\p{M}\\s]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        val new = first.text.lowercase().replace(Regex("[^\\p{L}\\p{N}\\p{M}\\s]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        if (old.isEmpty() || new.isEmpty()) return
        if (new.windowed(old.size).any { it == old }) repo.deleteTurn(meetingId, previous.id)
    }

    private fun words(text: String): List<String> = text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\p{M}\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    private fun chunkStartOf(chunk: MeetingChunkEntity): Long = chunk.startMs

    private fun workDir(meetingId: String): File = File(context.cacheDir, "meeting-$meetingId")

    /** Cleans up the per-meeting cache when a meeting is deleted. */
    fun forget(meetingId: String) {
        workDir(meetingId).deleteRecursively()
    }

    private companion object {
        const val TAG = "MeetingTranscriber"

        /** Five minutes of audio processes in about a minute; this only catches a dead connection. */
        const val ASR_TIMEOUT_SECONDS = 150L

        /** A chunk is tried this many times before the meeting is marked failed. */
        const val MAX_ATTEMPTS = 3
    }
}
