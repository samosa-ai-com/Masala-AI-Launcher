package com.samosaai.masala.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.samosaai.masala.R

/** The progress notification of a long job, and the foreground service type it runs under. */
object JobNotifications {
    const val CHANNEL = "jobs"
    const val NOTIFICATION_ID = 4101

    /**
     * Media work uses `mediaProcessing`, which exists from Android 15 (API 35); below that, and for
     * everything that isn't media (a scheduled skill run, uploads), it's `dataSync`.
     */
    fun serviceType(media: Boolean): Int =
        if (media && Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    fun foregroundInfo(context: Context, title: String, text: String, media: Boolean = false): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.jobs_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, serviceType(media))
    }
}
