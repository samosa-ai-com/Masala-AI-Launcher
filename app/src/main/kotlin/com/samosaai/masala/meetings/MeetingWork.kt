package com.samosaai.masala.meetings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.R
import com.samosaai.masala.core.meeting.MeetingStatus
import com.samosaai.masala.ui.meetings.MeetingActivity
import java.util.concurrent.TimeUnit

/**
 * The transcription queue for one meeting (plan §4.3): every closed chunk is transcribed in order,
 * and once nothing is pending the note is written. Runs as expedited-ish foreground work so a long
 * meeting can finish with the screen off, and stops cleanly when the network disappears
 * (`Result.retry()`), keeping the chunks on disk.
 *
 * Wi-Fi only is the default; the screen offers "Use mobile data" for a meeting that would otherwise
 * wait.
 */
class MeetingWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val meetingId = inputData.getString(KEY_MEETING) ?: return Result.failure()
        val app = applicationContext as? MasalaApp ?: return Result.failure()
        val meeting = app.meetings.get(meetingId) ?: return Result.success()
        if (meeting.status == MeetingStatus.FAILED) return Result.success()

        runCatching { setForeground(foreground(meeting.title, 0, 0)) }
        val drained = app.meetingTranscriber.drain(meetingId)
        if (!drained) {
            // Keep what worked. Three attempts per chunk is enough for a gateway hiccup; after that
            // the meeting is marked failed with a Retry in the UI, not an endless backoff loop.
            val chunks = app.meetings.chunksNow(meetingId)
            val exhausted = chunks.any { it.status == com.samosaai.masala.core.meeting.ChunkStatus.FAILED && it.attempts >= MAX_ATTEMPTS }
            runCatching { setForeground(foreground(meeting.title, 0, 0, failed = true)) }
            if (exhausted) {
                app.meetings.setStatus(meetingId, MeetingStatus.FAILED)
                app.meetings.setError(
                    meetingId,
                    chunks.firstOrNull { it.status == com.samosaai.masala.core.meeting.ChunkStatus.FAILED }?.error
                        ?: "Part of this meeting could not be transcribed. Retry from the meeting screen.",
                )
                return Result.success()
            }
            return Result.retry()
        }
        val latest = app.meetings.get(meetingId) ?: return Result.success()
        if (latest.status in MeetingStatus.active) {
            // Still recording: the next chunk rotation enqueues again.
            return Result.success()
        }
        val total = app.meetings.chunkCount(meetingId)
        val done = app.meetings.chunksNow(meetingId).count { it.status == com.samosaai.masala.core.meeting.ChunkStatus.DONE }
        runCatching { setForeground(foreground(latest.title, done, total)) }
        if (app.meetings.remainingChunks(meetingId) == 0) {
            val force = inputData.getBoolean(KEY_FORCE_NOTES, false)
            if (force || app.meetings.note(latest) == null) {
                app.meetingNotes.generate(meetingId)
            } else {
                app.meetings.setStatus(meetingId, MeetingStatus.READY)
            }
            if (!MeetingSettings(applicationContext).keepAudio) {
                app.meetings.deleteChunkFiles(meetingId)
            }
        } else {
            // A chunk left in an unknown state: stay transcribing and let the next run pick it up.
            app.meetings.setStatus(meetingId, MeetingStatus.TRANSCRIBING)
        }
        return Result.success()
    }

    private fun foreground(title: String, done: Int, total: Int, failed: Boolean = false): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, applicationContext.getString(R.string.meeting_transcribe_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MeetingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            failed -> applicationContext.getString(R.string.meeting_transcribe_retry)
            total > 0 -> applicationContext.getString(R.string.meeting_transcribe_progress, done, total)
            else -> applicationContext.getString(R.string.meeting_transcribe_start)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(title.ifBlank { applicationContext.getString(R.string.meeting_title) })
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(0), done.coerceAtLeast(0), total <= 0)
            .setContentIntent(open)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        const val KEY_MEETING = "meeting_id"
        const val KEY_FORCE_NOTES = "force_notes"
        private const val CHANNEL = "meeting_transcribe"
        private const val NOTIF_ID = 43

        /** Attempts per chunk before the meeting is marked failed instead of retried forever. */
        private const val MAX_ATTEMPTS = 3

        /** Queue a meeting; Wi-Fi only unless the user allowed mobile data for it. */
        fun enqueue(context: Context, meetingId: String, forceNotes: Boolean = false, anyNetwork: Boolean = false) {
            val wifiOnly = !anyNetwork && MeetingSettings(context).wifiOnly
            schedule(context, meetingId, forceNotes, if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED, ExistingWorkPolicy.KEEP)
        }

        /** "Use mobile data": replace a job that is waiting for Wi-Fi. */
        fun enqueueAnyNetwork(context: Context, meetingId: String, forceNotes: Boolean = false) {
            schedule(context, meetingId, forceNotes, NetworkType.CONNECTED, ExistingWorkPolicy.REPLACE)
        }

        private fun schedule(
            context: Context,
            meetingId: String,
            forceNotes: Boolean,
            network: NetworkType,
            policy: ExistingWorkPolicy,
        ) {
            val request = OneTimeWorkRequestBuilder<MeetingWork>()
                .setInputData(workDataOf(KEY_MEETING to meetingId, KEY_FORCE_NOTES to forceNotes))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("meeting")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("meeting-$meetingId", policy, request)
        }
    }
}
