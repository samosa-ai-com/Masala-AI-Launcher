package com.samosaai.masala.pro.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs an APK through a `PackageInstaller` session (plan Phase 1, lane C; APK export, Phase 5).
 *
 * Not copied from the private prototype, although the plan says so: the private prototype's `AppUpdateManager.installUpdate` hands the
 * APK to the system installer with an `ACTION_VIEW` intent through a FileProvider, and has no session
 * code. A session needs no FileProvider (which would be one more `:pro` provider to describe for Play),
 * writes the APK straight from app storage and reports how it ended. The permission handling
 * ([canInstall], [permissionSettingsIntent]) is the private prototype's.
 *
 * `REQUEST_INSTALL_PACKAGES` is a Play-restricted permission and the user still confirms every install
 * in Android's own dialog.
 */
class ApkInstaller(private val context: Context) {

    /** The outcome of one [install]. [message] is for the user or the model. */
    data class Outcome(val success: Boolean, val message: String)

    /** From the private prototype: whether the user has allowed this app to install apps. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** From the private prototype: the system screen where the user allows it. */
    fun permissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Writes [apk] into a session and commits it, then waits for the install to end. The system asks the
     * user to confirm (the receiver starts that dialog), so the wait is long.
     */
    suspend fun install(apk: File, timeoutMs: Long = CONFIRM_TIMEOUT_MS): Outcome = withContext(Dispatchers.IO) {
        if (!apk.isFile) return@withContext Outcome(false, "${apk.path} is not a file.")
        if (!canInstall()) return@withContext Outcome(false, "Masala is not allowed to install apps yet.")

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val done = CompletableDeferred<Outcome>()
        pending[sessionId] = done
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                    // Mutable: the system adds the status and the confirmation intent to it.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
            withTimeoutOrNull(timeoutMs) { done.await() }
                ?: Outcome(false, "Nobody confirmed the install in time.").also { runCatching { installer.abandonSession(sessionId) } }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            Outcome(false, "Could not install: ${e.message}")
        } finally {
            pending.remove(sessionId)
        }
    }

    companion object {
        private const val CONFIRM_TIMEOUT_MS = 5 * 60 * 1000L

        private val pending = ConcurrentHashMap<Int, CompletableDeferred<Outcome>>()

        /** Called by [InstallResultReceiver] with a session's final status. */
        internal fun finish(sessionId: Int, success: Boolean, message: String) {
            pending.remove(sessionId)?.complete(Outcome(success, message))
        }
    }
}
