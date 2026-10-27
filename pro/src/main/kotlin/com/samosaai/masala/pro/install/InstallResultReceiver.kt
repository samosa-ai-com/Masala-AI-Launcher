package com.samosaai.masala.pro.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Gets a `PackageInstaller` session's status from [ApkInstaller]. Not exported: the PendingIntent is fired
 * by the system with Masala's identity. For "needs the user" it starts the system's confirmation dialog.
 */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> ApkInstaller.finish(sessionId, true, "Installed.")
            PackageInstaller.STATUS_FAILURE_ABORTED -> ApkInstaller.finish(sessionId, false, "The install was cancelled.")
            else -> ApkInstaller.finish(sessionId, false, "The install failed ($status): $message")
        }
    }
}
