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
import com.samosaai.masala.chat.ChatResult
import com.samosaai.masala.chat.TurnRole
import com.samosaai.masala.miniapps.MiniAppOpenGuard

/**
 * The quiet "your reply is ready" notification (Phase A): a turn the user started
 * finishes while Masala isn't visible, so the answer doesn't sit unseen in a
 * closed chat. Watching it finish never notifies. One live notice at a time
 * (fixed id, updated in place); tapping it opens that thread, and it clears.
 */
object ReplyDoneNotification {
    private const val CHANNEL = "replies"
    private const val ID = 4301

    fun post(context: Context, chatId: String, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.replies_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_OPEN_CHAT, chatId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_masala)
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(ID, notification)
    }
}

/**
 * Whether a finished turn earns a notification: a Masala answer or error, the
 * user opted in, home wasn't visible when it landed, and the turn didn't just
 * open something itself (an auto-opened mini app is its own notice).
 */
internal fun shouldNotifyReply(
    role: TurnRole,
    text: String,
    results: List<ChatResult>,
    notifyEnabled: Boolean,
    homeVisible: Boolean,
    isFreshlyOpened: (String) -> Boolean = MiniAppOpenGuard::isFreshlyOpened,
): Boolean {
    if (role != TurnRole.MASALA && role != TurnRole.ERROR) return false
    if (!notifyEnabled || homeVisible) return false
    if (text.isBlank()) return false
    if (results.any { it.kind == "mini_app" && isFreshlyOpened(it.ref) }) return false
    return true
}
