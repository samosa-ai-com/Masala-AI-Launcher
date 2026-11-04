package com.samosaai.masala.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.samosaai.masala.BuildConfig
import com.samosaai.masala.R
import com.samosaai.masala.core.update.AppUpdates
import com.samosaai.masala.core.update.UpdateManifest
import com.samosaai.masala.ui.settings.SettingsActivity
import java.util.concurrent.TimeUnit

/** Whether to announce new versions, and which one was announced last. Not personal data. */
class UpdatePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("masala_updates", Context.MODE_PRIVATE)

    var notify: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, true)
        set(value) { prefs.edit().putBoolean(KEY_NOTIFY, value).apply() }

    var lastNotifiedVersionCode: Int
        get() = prefs.getInt(KEY_LAST_NOTIFIED, 0)
        // commit: a worker's process may go as soon as it returns.
        set(value) { prefs.edit().putInt(KEY_LAST_NOTIFIED, value).commit() }

    private companion object {
        const val KEY_NOTIFY = "notify"
        const val KEY_LAST_NOTIFIED = "last_notified_version_code"
    }
}

/**
 * Checks `update.json` every [AppUpdates.CHECK_INTERVAL_HOURS] hours on a connected network and posts
 * one notification per new version. Release builds only ([BuildConfig.UPDATE_CHECKS]). A failed check
 * or a blocked notification is simply tried again next time; only a posted one marks its version as
 * announced.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = UpdatePrefs(applicationContext)
        if (!BuildConfig.UPDATE_CHECKS || !prefs.notify) return Result.success()
        val check = UpdateChecker().check()
        val manifest = (check as? UpdateChecker.Check.Available)?.manifest ?: return Result.success()
        if (AppUpdates.shouldNotify(BuildConfig.VERSION_CODE, manifest, prefs.lastNotifiedVersionCode) &&
            UpdateNotification.post(applicationContext, manifest)
        ) {
            prefs.lastNotifiedVersionCode = manifest.versionCode
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "update-check"

        /** Keeps the periodic check armed while it is wanted, and cancels it otherwise. */
        fun ensure(context: Context) {
            val work = WorkManager.getInstance(context)
            if (!BuildConfig.UPDATE_CHECKS || !UpdatePrefs(context).notify) {
                work.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(AppUpdates.CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** "Masala x.y.z is available"; tapping it opens Settings → About, where the update is installed. */
object UpdateNotification {
    private const val CHANNEL = "updates"
    private const val ID = 4401

    /** Returns whether the notification could be posted. */
    fun post(context: Context, manifest: UpdateManifest): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(
            context, ID,
            Intent(context, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_PAGE, "ABOUT")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_masala)
            .setContentTitle(context.getString(R.string.update_notification_title, manifest.versionName))
            .setContentText(context.getString(R.string.update_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(ID, notification)
        return true
    }
}
