package com.samosaai.masala.tools

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * A special access a group of tools depends on, granted on a system screen rather than through the
 * runtime permission dialog. From the private prototype's CapabilityCatalog, without its accessibility, device admin,
 * root and overlay entries (Masala has none of those tools), and with all-files access, Termux and
 * "install unknown apps" for `:pro` (plan Phase 1, lane B), plus usage access and Do Not Disturb,
 * which the private prototype's tools ask for themselves.
 */
enum class Capability(
    /** Human name, used in the message to the model and on the debug screen. */
    val label: String,
    /** Tools that can't work without it. */
    val tools: Set<String>,
    /** The [ActionResult] marker for it, or null when there's no screen to open (Termux is installed, not granted). */
    val marker: String?,
) {
    USAGE_ACCESS("usage access", setOf("get_app_usage"), ActionResult.USAGE_ACCESS),
    DND_ACCESS("Do Not Disturb access", setOf("set_dnd"), ActionResult.DND_ACCESS),

    // Media sessions are only handed to an enabled notification listener, hence the media tools.
    NOTIFICATION_LISTENER(
        "notification access",
        setOf("read_notifications", "dismiss_notifications", "media_control", "get_now_playing"),
        ActionResult.NOTIFICATION_LISTENER_ACCESS,
    ),
    HEALTH_CONNECT("Health Connect", setOf("get_health_summary", "get_health_records"), ActionResult.HEALTH_CONNECT),

    // :pro (lane C registers these tools).
    ALL_FILES("all-files access", setOf("list_files", "read_file", "write_file", "glob", "grep", "file_search"), "special:all_files_access"),
    TERMUX("Termux (from F-Droid, with ffmpeg)", setOf("media_convert"), null),

    // The APK export tool joins this in Phase 5.
    INSTALL_UNKNOWN_APPS("permission to install apps", emptySet(), "special:install_unknown_apps"),
    ;

    fun isAvailable(context: Context): Boolean = when (this) {
        USAGE_ACCESS -> context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
        DND_ACCESS -> context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        NOTIFICATION_LISTENER -> context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        // Built into Android 14+; before that it's an app (checked properly with the Health tools in B2).
        HEALTH_CONNECT -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            isInstalled(context, "com.google.android.apps.healthdata")
        ALL_FILES -> Environment.isExternalStorageManager()
        TERMUX -> isInstalled(context, "com.termux")
        INSTALL_UNKNOWN_APPS -> context.packageManager.canRequestPackageInstalls()
    }

    /** The system screen where the user grants it, or null. */
    fun settingsIntent(context: Context): Intent? {
        val pkg = Uri.fromParts("package", context.packageName, null)
        return when (this) {
            USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            HEALTH_CONNECT -> null
            ALL_FILES -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
            TERMUX -> null
            INSTALL_UNKNOWN_APPS -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg)
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        /** The capability [tool] needs, or null if it needs none. */
        fun of(tool: String): Capability? = entries.firstOrNull { tool in it.tools }

        fun byMarker(marker: String): Capability? = entries.firstOrNull { it.marker == marker }

        private fun isInstalled(context: Context, packageName: String): Boolean = try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
