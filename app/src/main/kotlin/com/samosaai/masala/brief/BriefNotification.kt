package com.samosaai.masala.brief

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.samosaai.masala.HomeActivity
import com.samosaai.masala.R
import com.samosaai.masala.core.brief.BriefSpeech

/** The quiet "your brief is ready" notification (Settings → Daily brief); tapping it opens home. */
object BriefNotification {
    private const val CHANNEL = "brief"
    private const val ID = 4201

    fun post(context: Context, markdown: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.brief_channel), NotificationManager.IMPORTANCE_LOW))
        val text = BriefSpeech.toSpeech(markdown)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_masala)
            .setContentTitle(context.getString(R.string.brief_ready))
            .setContentText(text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(ID, notification)
    }
}
