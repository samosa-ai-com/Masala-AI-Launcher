package com.samosaai.masala.notifications

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

/**
 * The "Masala is still working" notice (plan `docs/plans/chat-background.md`): a
 * turn the user started keeps running after the chat is minimized, closed or
 * covered, so the progress stays visible. One live notice at a time (fixed id,
 * updated in place); tapping it reopens the current thread, and it clears when
 * the turn lands. Informational, not a foreground service: the work stays in
 * the app scope. Silent when notifications aren't granted.
 */
object ChatWorkingNotification {
    private const val CHANNEL = "chat_working"
    private const val ID = 4302

    fun show(context: Context, chatId: String, step: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.chat_working_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_OPEN_CHAT, chatId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_masala)
            .setContentTitle(context.getString(R.string.chat_working_title))
            .setContentText(step.ifBlank { context.getString(R.string.chat_working_body) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(step.ifBlank { context.getString(R.string.chat_working_body) }))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(ID, notification)
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
