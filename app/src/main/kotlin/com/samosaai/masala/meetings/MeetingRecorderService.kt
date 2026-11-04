package com.samosaai.masala.meetings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.R
import com.samosaai.masala.audio.RecordingService
import com.samosaai.masala.core.meeting.ChunkStatus
import com.samosaai.masala.core.meeting.MeetingStatus
import com.samosaai.masala.ui.meetings.MeetingActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A meeting recording that can run for hours.
 *
 * One `microphone` foreground service, one session row, one AAC file per [CHUNK_MINUTES] minutes in
 * `files/meetings/<id>/`. Chunking is what makes a three-hour meeting safe: each piece stays under
 * the transcriber's ten-minute request limit, a killed process loses at most the open chunk, and a
 * closed chunk can be uploaded while the meeting continues.
 *
 * Pause closes the current file (so paused time is silence, not audio) and resume opens the next one;
 * the elapsed clock excludes the gap. The notification updates its own chronometer from the same
 * numbers the screen shows.
 */
class MeetingRecorderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: MediaRecorder? = null
    private var meetingId: String? = null
    private var title: String = ""
    private var template: String = "general"
    private var language: String = ""
    private var chunkIndex = 0
    /** Meeting-clock time when the open chunk began. */
    private var chunkStartMs = 0L
    /** Active (unpaused) time accumulated before the current run. */
    private var baseMs = 0L
    /** Elapsed real time when the current run started. */
    private var runningSinceElapsed = 0L
    private var heartbeat: Job? = null
    private var amplitudeJob: Job? = null
    private var starting = false
    private var startFailure: String? = null
    /** The file currently being written: its row is OPEN and it is not in the queue. */
    private var openChunk: OpenChunk? = null
    /** The insert of the open chunk's row, so a close can wait for it instead of racing it. */
    private var openInsert: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Restarted after a kill: the mic cannot be opened from the background, so end honestly.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> stopMeeting()
            ACTION_PAUSE -> pauseMeeting()
            ACTION_RESUME -> resumeMeeting()
            else -> startMeeting(
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                template = intent.getStringExtra(EXTRA_TEMPLATE).orEmpty(),
                language = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty(),
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (meetingId != null && _state.value.status in MeetingStatus.active) {
            // A stopService (or the system) must not throw away the meeting: finalize what exists.
            // The service's own scope is cancelled on the next line, so the writes run in the app's.
            val app = applicationContext as? MasalaApp
            val id = meetingId
            val elapsed = elapsedMs()
            val recording = _state.value.status == MeetingStatus.RECORDING
            val closed = if (recording) closeOpenChunk((elapsed - chunkStartMs).coerceAtLeast(0)) else null
            app?.scope?.launch {
                if (id != null) closed?.let { saveClosed(id, it) }
                runCatching { app.meetings.finish(id ?: return@runCatching, MeetingStatus.INTERRUPTED, elapsed) }
                runCatching { id?.let { MeetingWork.enqueue(this@MeetingRecorderService, it) } }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start / pause / stop

    private fun startMeeting(title: String, template: String, language: String) {
        if (starting || meetingId != null) return
        val app = applicationContext as? MasalaApp ?: return
        if (RecordingService.recording.value) {
            _state.value = State(error = getString(R.string.meeting_quick_recorder_busy))
            stopSelf()
            return
        }
        starting = true
        startFailure = null
        // Foreground immediately: Android gives a service a few seconds and we ask for the mic.
        ensureChannel()
        startForegroundCompat(buildNotification(getString(R.string.meeting_starting), running = false))
        _state.value = State(status = MeetingStatus.RECORDING, title = title)
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    app.meetings.create(
                        title = title,
                        template = template.ifBlank { app.meetingSettings.template },
                        language = language.ifBlank { app.meetingSettings.language },
                    )
                }
                meetingId = session.id
                this@MeetingRecorderService.title = session.title
                this@MeetingRecorderService.template = session.template
                this@MeetingRecorderService.language = session.language
                chunkIndex = 0
                chunkStartMs = 0
                baseMs = 0
                runningSinceElapsed = SystemClock.elapsedRealtime()
                beginChunk()
                starting = false
                if (startFailure != null) {
                    // The row exists: publish it and stay paused so the screen can show the error
                    // and let the user Resume, instead of leaving a "recording" row nobody owns.
                    _state.value = _state.value.copy(
                        meetingId = session.id,
                        title = session.title,
                        error = startFailure,
                        status = MeetingStatus.PAUSED,
                    )
                    withContext(Dispatchers.IO) {
                        app.meetings.setStatus(session.id, MeetingStatus.PAUSED)
                        app.meetings.setError(session.id, startFailure)
                    }
                    updateNotification()
                    return@launch
                }
                _state.value = _state.value.copy(
                    meetingId = session.id,
                    title = session.title,
                    status = MeetingStatus.RECORDING,
                )
                startHeartbeat()
                startAmplitude()
                updateNotification()
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the meeting", e)
                startFailure = e.message
                starting = false
                _state.value = State(status = MeetingStatus.READY, error = e.message)
                stopSelf()
            }
        }
    }

    private fun pauseMeeting() {
        if (meetingId == null || _state.value.status != MeetingStatus.RECORDING) return
        val elapsed = elapsedMs()
        val duration = (elapsed - chunkStartMs).coerceAtLeast(0)
        val closed = closeOpenChunk(duration)
        baseMs = elapsed
        heartbeat?.cancel()
        amplitudeJob?.cancel()
        _state.value = _state.value.copy(status = MeetingStatus.PAUSED, elapsedMs = elapsed, amplitude = 0f)
        closeInBackground(closed) {
            meetingId?.let { id ->
                (applicationContext as? MasalaApp)?.meetings?.heartbeat(id, elapsed)
                (applicationContext as? MasalaApp)?.meetings?.setStatus(id, MeetingStatus.PAUSED)
            }
            updateNotification()
        }
    }

    private fun resumeMeeting() {
        if (meetingId == null || _state.value.status != MeetingStatus.PAUSED) return
        runningSinceElapsed = SystemClock.elapsedRealtime()
        chunkStartMs = baseMs
        if (!beginChunk()) {
            // The mic did not open: stay paused rather than counting time with no recorder.
            _state.value = _state.value.copy(status = MeetingStatus.PAUSED, error = startFailure)
            return
        }
        _state.value = _state.value.copy(status = MeetingStatus.RECORDING, error = null)
        startHeartbeat()
        startAmplitude()
        scope.launch {
            meetingId?.let { id -> (applicationContext as? MasalaApp)?.meetings?.setStatus(id, MeetingStatus.RECORDING) }
            updateNotification()
        }
    }

    private fun stopMeeting() {
        if (meetingId == null) {
            stopSelf()
            return
        }
        val elapsed = elapsedMs()
        val recording = _state.value.status == MeetingStatus.RECORDING
        val duration = if (recording) (elapsed - chunkStartMs).coerceAtLeast(0) else 0
        val closed = if (recording) closeOpenChunk(duration) else null
        baseMs = elapsed
        heartbeat?.cancel()
        amplitudeJob?.cancel()
        _state.value = _state.value.copy(status = MeetingStatus.TRANSCRIBING, elapsedMs = elapsed, amplitude = 0f)
        val app = applicationContext as? MasalaApp
        // The app scope, not the service's: stopSelf() cancels this scope, and the last chunk must
        // be closed *before* the worker is enqueued or it is skipped as still open.
        app?.scope?.launch {
            meetingId?.let { id -> closed?.let { saveClosed(id, it) } }
            finishSession(MeetingStatus.TRANSCRIBING)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun finishSession(status: String) {
        val app = applicationContext as? MasalaApp ?: return
        val id = meetingId ?: return
        val elapsed = elapsedMs()
        runCatching { app.meetings.finish(id, status, elapsed) }
        // Only the last chunk can be short; hand everything to the transcribing worker.
        runCatching { MeetingWork.enqueue(this@MeetingRecorderService, id) }
        meetingId = null
        _state.value = State(status = MeetingStatus.READY, elapsedMs = elapsed, meetingId = id)
    }

    // ---------------------------------------------------------------- recording chunks

    /** Opens the next chunk file. False when the mic or the encoder refused. */
    @Synchronized
    private fun beginChunk(): Boolean {
        val app = applicationContext as? MasalaApp ?: return false
        val id = meetingId ?: return false
        recorder?.runCatching { stop() }
        recorder?.runCatching { release() }
        recorder = null
        // A three-hour meeting needs room; running out mid-chunk loses the part being written.
        val free = runCatching { android.os.StatFs(filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < MIN_FREE_BYTES) {
            startFailure = getString(R.string.meeting_low_storage)
            _state.value = _state.value.copy(error = startFailure, status = MeetingStatus.PAUSED)
            return false
        }
        val idx = chunkIndex
        val file = app.meetings.chunkFile(id, idx)
        file.parentFile?.mkdirs()
        var next: MediaRecorder? = null
        return try {
            next = newRecorder(file)
            next.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) rotateChunk()
            }
            next.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Recorder error what=$what extra=$extra")
                onRecorderError(getString(R.string.meeting_mic_lost))
                true
            }
            next.prepare()
            next.start()
            recorder = next
            // The chunk starts when the recorder does, not when the previous file was closed.
            chunkStartMs = elapsedMs()
            openChunk = OpenChunk(idx, file, chunkStartMs)
            openInsert = app.scope.launch {
                runCatching {
                    // OPEN until closeChunk: a growing file must never enter the queue.
                    app.meetings.addChunk(id, idx, file, chunkStartMs, ChunkStatus.OPEN)
                }
            }
            chunkIndex = idx + 1
            _state.value = _state.value.copy(chunks = chunkIndex)
            Log.i(TAG, "Recording ${file.name} at ${chunkStartMs}ms")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Mic unavailable", e)
            next?.runCatching { release() }
            recorder = null
            startFailure = e.message ?: getString(R.string.meeting_mic_unavailable)
            _state.value = _state.value.copy(error = startFailure, status = MeetingStatus.PAUSED)
            false
        }
    }

    /** The 5-minute mark: close this file, open the next, and let the queue transcribe the closed one. */
    private fun rotateChunk() {
        if (meetingId == null || _state.value.status != MeetingStatus.RECORDING) return
        val elapsed = elapsedMs()
        val duration = (elapsed - chunkStartMs).coerceAtLeast(0)
        val closed = closeOpenChunk(duration)
        if (!beginChunk()) {
            // The next file could not be opened (low storage, mic lost): pause instead of counting
            // time with no recorder behind it.
            baseMs = elapsed
            heartbeat?.cancel()
            amplitudeJob?.cancel()
            _state.value = _state.value.copy(
                status = MeetingStatus.PAUSED,
                elapsedMs = elapsed,
                error = startFailure,
                amplitude = 0f,
            )
            val app = applicationContext as? MasalaApp
            app?.scope?.launch {
                meetingId?.let { id ->
                    closed?.let { saveClosed(id, it) }
                    app.meetings.setStatus(id, MeetingStatus.PAUSED)
                    app.meetings.setError(id, startFailure)
                    runCatching { MeetingWork.enqueue(this@MeetingRecorderService, id) }
                }
                updateNotification()
            }
            return
        }
        val app = applicationContext as? MasalaApp
        app?.scope?.launch {
            meetingId?.let { id ->
                closed?.let { saveClosed(id, it) }
                runCatching { MeetingWork.enqueue(this@MeetingRecorderService, id) }
            }
        }
        updateNotification()
    }

    /** Stops the open recorder and returns the chunk it finished. */
    private fun closeOpenChunk(durationMs: Long): ClosedChunk? {
        recorder?.runCatching { stop() }
        recorder?.runCatching { release() }
        recorder = null
        val open = openChunk
        openChunk = null
        if (open == null) return null
        return ClosedChunk(open.idx, durationMs, open.path, open.startMs)
    }

    private suspend fun saveClosed(id: String, closed: ClosedChunk) {
        val app = applicationContext as? MasalaApp ?: return
        // The OPEN insert is launched before this; if it landed after the close it would overwrite
        // the row back to "open" with a zero duration. Wait for it, then close last.
        openInsert?.join()
        runCatching {
            // Upsert first: the OPEN insert may not have landed before a stop right after a start.
            app.meetings.addChunk(id, closed.idx, closed.path, closed.startMs, ChunkStatus.OPEN)
            app.meetings.closeChunk(id, closed.idx, closed.durationMs)
            app.meetings.heartbeat(id, elapsedMs())
        }.onFailure { Log.w(TAG, "Closing the last chunk failed", it) }
    }

    /** Runs a close/save sequence in the app scope, which outlives stopSelf(). */
    private fun closeInBackground(closed: ClosedChunk?, then: suspend () -> Unit) {
        val app = applicationContext as? MasalaApp
        val id = meetingId
        app?.scope?.launch {
            if (id != null) closed?.let { saveClosed(id, it) }
            runCatching { then() }
        }
    }

    private fun onRecorderError(message: String) {
        val elapsed = elapsedMs()
        val closed = closeOpenChunk((elapsed - chunkStartMs).coerceAtLeast(0))
        baseMs = elapsed
        heartbeat?.cancel()
        amplitudeJob?.cancel()
        _state.value = _state.value.copy(status = MeetingStatus.PAUSED, elapsedMs = elapsed, error = message, amplitude = 0f)
        closeInBackground(closed) {
            meetingId?.let { id ->
                (applicationContext as? MasalaApp)?.meetings?.setStatus(id, MeetingStatus.PAUSED)
                (applicationContext as? MasalaApp)?.meetings?.setError(id, message)
            }
            updateNotification()
        }
    }

    private fun newRecorder(file: File): MediaRecorder {
        @Suppress("DEPRECATION")
        val created = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioSamplingRate(16_000)
            created.setAudioChannels(1)
            // 48 kbps mono at 16 kHz: ~21 MB an hour, and enough detail for a room recording.
            created.setAudioEncodingBitRate(48_000)
            created.setOutputFile(file.absolutePath)
            created.setMaxDuration((CHUNK_MINUTES * 60_000L).toInt())
        } catch (e: Exception) {
            created.release()
            throw e
        }
        return created
    }

    // ---------------------------------------------------------------- state, heartbeat, notification

    private data class OpenChunk(val idx: Int, val path: File, val startMs: Long)
    private data class ClosedChunk(val idx: Int, val durationMs: Long, val path: File, val startMs: Long)

    private fun elapsedMs(): Long = when (_state.value.status) {
        MeetingStatus.RECORDING -> baseMs + (SystemClock.elapsedRealtime() - runningSinceElapsed)
        else -> baseMs.coerceAtLeast(_state.value.elapsedMs)
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                val id = meetingId ?: break
                val app = applicationContext as? MasalaApp ?: break
                val elapsed = elapsedMs()
                _state.value = _state.value.copy(elapsedMs = elapsed)
                runCatching { app.meetings.heartbeat(id, elapsed) }
                updateNotification()
            }
        }
    }

    private fun startAmplitude() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch(Dispatchers.Default) {
            while (true) {
                val level = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                // A talking room sits around 2–8k; sqrt keeps a whisper visible without pinning the bar.
                val normalized = kotlin.math.sqrt(level / 32767.0).toFloat().coerceIn(0f, 1f)
                _state.value = _state.value.copy(amplitude = normalized)
                delay(120)
            }
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.meeting_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    enableVibration(false)
                },
            )
        }
    }

    private fun updateNotification() {
        val notification = buildNotification(
            text = when (_state.value.status) {
                MeetingStatus.PAUSED -> getString(R.string.meeting_notif_paused)
                MeetingStatus.TRANSCRIBING -> getString(R.string.meeting_notif_finishing)
                else -> getString(R.string.meeting_notif_recording)
            },
            running = _state.value.status == MeetingStatus.RECORDING,
        )
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String, running: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            MeetingActivity.intent(this, meetingId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val paused = _state.value.status == MeetingStatus.PAUSED
        val toggle = PendingIntent.getService(
            this,
            1,
            Intent(this, MeetingRecorderService::class.java)
                .setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, MeetingRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val elapsed = _state.value.elapsedMs
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(title.ifBlank { getString(R.string.meeting_title) })
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (running) {
            // The chronometer counts from (now - elapsed), so it shows the active time, not wall time.
            builder.setWhen(System.currentTimeMillis() - elapsed).setUsesChronometer(true)
        } else {
            builder.setWhen(System.currentTimeMillis()).setUsesChronometer(false)
            builder.setSubText(com.samosaai.masala.core.audio.DiarizedTranscript.clockOf(elapsed))
        }
        if (_state.value.status != MeetingStatus.TRANSCRIBING) {
            builder.addAction(0, getString(if (paused) R.string.meeting_resume else R.string.meeting_pause), toggle)
            builder.addAction(0, getString(R.string.meeting_stop), stop)
        }
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** What the live screen reads: one source of truth for the timer, the bars and the status. */
    data class State(
        val meetingId: String? = null,
        val title: String = "",
        val status: String = MeetingStatus.READY,
        val elapsedMs: Long = 0,
        val amplitude: Float = 0f,
        val chunks: Int = 0,
        val error: String? = null,
    ) {
        val active: Boolean get() = status == MeetingStatus.RECORDING || status == MeetingStatus.PAUSED
    }

    companion object {
        const val TAG = "MeetingRecorder"
        const val ACTION_START = "com.samosaai.masala.meeting.START"
        const val ACTION_PAUSE = "com.samosaai.masala.meeting.PAUSE"
        const val ACTION_RESUME = "com.samosaai.masala.meeting.RESUME"
        const val ACTION_STOP = "com.samosaai.masala.meeting.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEMPLATE = "template"
        const val EXTRA_LANGUAGE = "language"

        /** Each file covers five minutes: under the endpoint's ten-minute cap with room to retry. */
        const val CHUNK_MINUTES = 5L
        private const val CHANNEL = "meeting_recording"
        private const val NOTIF_ID = 42
        private const val HEARTBEAT_MS = 30_000L

        /** Below this the recorder pauses with a message instead of failing mid-chunk. */
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024

        private val _state = MutableStateFlow(State())

        /** True while a meeting runs in this process; the widget and the screens read it. */
        val state: StateFlow<State> = _state.asStateFlow()

        fun startIntent(context: Context, title: String = "", template: String = "", language: String = ""): Intent =
            Intent(context, MeetingRecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEMPLATE, template)
                .putExtra(EXTRA_LANGUAGE, language)

        fun command(context: Context, action: String): Intent =
            Intent(context, MeetingRecorderService::class.java).setAction(action)
    }
}
