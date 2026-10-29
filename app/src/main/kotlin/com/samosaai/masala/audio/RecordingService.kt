package com.samosaai.masala.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.samosaai.masala.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Meeting-notes recording (plan Phase 3, Lane D): a `microphone` foreground service started from
 * an explicit tap, with a Stop action in the notification. 32 kbps mono AAC, one file per
 * [CHUNK_MINUTES] so each piece stays under the transcription limits; the oldest files are pruned
 * past [KEEP_FILES].
 *
 * Chunking rides `MediaRecorder`'s own max-duration callback rather than a timer thread: no second
 * thread to race with Stop, and no gap between chunks.
 */
class RecordingService : Service() {
    private var recorder: MediaRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is a restart after the process was killed: starting a microphone FGS from
        // the background would be refused, so it stops instead of crashing.
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startRecording()
        }
        return START_NOT_STICKY
    }

    /** Covers `stopService()`, which never delivers [ACTION_STOP]. */
    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    @Synchronized
    private fun startRecording() {
        if (recorder != null) return
        ensureChannel()
        startForeground(NOTIF_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (!beginChunk()) {
            stopRecording()
            stopSelf()
            return
        }
        _recording.value = true
    }

    /** Opens the next chunk file. False when the mic or the encoder refused. */
    @Synchronized
    private fun beginChunk(): Boolean {
        // Separately, so a failed stop() never skips release().
        recorder?.runCatching { stop() }
        recorder?.runCatching { release() }
        recorder = null
        val dir = recordingsDir().apply { mkdirs() }
        RecordingFiles.prune(dir, KEEP_FILES)
        val startedAt = System.currentTimeMillis()
        val file = File(dir, RecordingFiles.chunkName(startedAt))
        // Held outside the try, so a recorder that fails while being set up is still released.
        var next: MediaRecorder? = null
        return try {
            next = newRecorder(file)
            next.apply {
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        // Chunk full: start the next one; the service keeps running.
                        if (!beginChunk()) stopRecording().also { stopSelf() }
                    }
                }
                setOnErrorListener { _, what, extra ->
                    // Mic taken by a call or the encoder failed: stop cleanly, keep the files.
                    Log.w(TAG, "Recorder error what=$what extra=$extra")
                    stopRecording()
                    stopSelf()
                }
                prepare()
                start()
            }
            recorder = next
            Log.i(TAG, "Recording to ${file.name}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Mic unavailable", e)
            next?.runCatching { release() }
            recorder = null
            false
        }
    }

    private fun newRecorder(file: File): MediaRecorder {
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(16_000)
            recorder.setAudioChannels(1)
            recorder.setAudioEncodingBitRate(32_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.setMaxDuration((CHUNK_MINUTES * 60_000L).toInt())
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
        return recorder
    }

    @Synchronized
    private fun stopRecording() {
        recorder?.runCatching { stop() }
        recorder?.runCatching { release() }
        recorder = null
        _recording.value = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun recordingsDir() = File(filesDir, "recordings")

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(getString(R.string.recording_active))
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.recording_stop),
                stop,
            )
            .build()
    }

    companion object {
        const val TAG = "RecordingService"
        const val ACTION_STOP = "com.samosaai.masala.audio.STOP"
        const val CHUNK_MINUTES = 15L
        const val KEEP_FILES = 48
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 41

        private val _recording = MutableStateFlow(false)

        /** What the settings page shows: true while a recording is running in this process. */
        val recording: StateFlow<Boolean> = _recording.asStateFlow()
    }
}

/** Pure file rotation for recordings: chunk names and retention pruning (unit-tested, no device). */
object RecordingFiles {
    fun chunkName(startedAt: Long): String = "rec-%d.m4a".format(startedAt)

    /** Deletes oldest chunks past [keep], ordered by their timestamp, not by string order. */
    fun prune(dir: File, keep: Int): List<String> {
        val chunks = dir.listFiles { f -> f.isFile && f.name.startsWith("rec-") && f.name.endsWith(".m4a") }
            ?.sortedBy { stampOf(it) }.orEmpty()
        val drop = chunks.dropLast(keep.coerceAtLeast(0))
        drop.forEach { runCatching { it.delete() } }
        return drop.map { it.name }
    }

    private fun stampOf(file: File): Long =
        file.nameWithoutExtension.removePrefix("rec-").toLongOrNull() ?: file.lastModified()
}
