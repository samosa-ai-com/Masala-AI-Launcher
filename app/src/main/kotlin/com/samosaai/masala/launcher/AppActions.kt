package com.samosaai.masala.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.samosaai.masala.core.apps.AppKey

/**
 * Starting apps and shortcuts, app info and uninstall. Everything goes through `LauncherApps`, which
 * works across profiles and asks to turn work apps on when they're paused.
 */
class AppActions(private val context: Context, private val catalog: AppCatalog) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    fun launch(key: AppKey, bounds: Rect? = null): Boolean = tryStart {
        launcherApps.startMainActivity(catalog.componentName(key), catalog.user(key.user), bounds, null)
    }

    fun appInfo(key: AppKey): Boolean = tryStart {
        launcherApps.startAppDetailsActivity(catalog.componentName(key), catalog.user(key.user), null, null)
    }

    /** Android's own uninstall dialog, for the right profile. */
    fun uninstall(key: AppKey): Boolean = tryStart {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", key.packageName, null))
                .putExtra(Intent.EXTRA_USER, catalog.user(key.user))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // Shortcuts. Only the default home app may read or start them (hasShortcutHostPermission).

    fun canUseShortcuts(): Boolean = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)

    /** The app's own shortcuts (static, then dynamic), as its long-press menu shows them. */
    fun shortcuts(key: AppKey): List<ShortcutInfo> {
        val query = ShortcutQuery()
            .setPackage(key.packageName)
            .setActivity(catalog.componentName(key))
            .setQueryFlags(ShortcutQuery.FLAG_MATCH_MANIFEST or ShortcutQuery.FLAG_MATCH_DYNAMIC)
        return query(query, key.user)
            .filter { it.isEnabled }
            .sortedWith(compareBy<ShortcutInfo> { !it.isDeclaredInManifest }.thenBy { it.rank })
            .take(MAX_MENU_SHORTCUTS)
    }

    /** Shortcuts pinned to home: the system keeps the list for the default home app. */
    fun pinnedShortcuts(): List<ShortcutInfo> =
        catalog.state.value.profiles.filterNot { it.paused }.flatMap { profile ->
            query(ShortcutQuery().setQueryFlags(ShortcutQuery.FLAG_MATCH_PINNED), profile.serial)
        }

    /** Starts the shortcut [id] of [packageName] (static, dynamic or pinned) for the profile [user]. */
    fun startShortcut(packageName: String, id: String, user: Long): Boolean {
        val query = ShortcutQuery()
            .setPackage(packageName)
            .setShortcutIds(listOf(id))
            .setQueryFlags(ShortcutQuery.FLAG_MATCH_MANIFEST or ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED)
        return query(query, user).firstOrNull { it.isEnabled }?.let { startShortcut(it) } ?: false
    }

    fun startShortcut(info: ShortcutInfo, bounds: Rect? = null): Boolean = tryStart {
        launcherApps.startShortcut(info, bounds, null)
    }

    fun pin(info: ShortcutInfo) = setPinned(info, pinned = true)

    fun unpin(info: ShortcutInfo) = setPinned(info, pinned = false)

    private fun setPinned(info: ShortcutInfo, pinned: Boolean) {
        val serial = catalog.serial(info.userHandle)
        val query = ShortcutQuery().setPackage(info.`package`).setQueryFlags(ShortcutQuery.FLAG_MATCH_PINNED)
        val ids = query(query, serial).map { it.id }.toMutableSet()
        if (pinned) ids += info.id else ids -= info.id
        runCatching { launcherApps.pinShortcuts(info.`package`, ids.toList(), info.userHandle) }
            .onFailure { Log.w(TAG, "Can't change pinned shortcuts", it) }
    }

    private fun query(query: ShortcutQuery, serial: Long): List<ShortcutInfo> {
        if (!canUseShortcuts()) return emptyList()
        val user = catalog.user(serial) ?: return emptyList()
        // Throws while the profile is locked or paused.
        return runCatching { launcherApps.getShortcuts(query, user) }.getOrNull().orEmpty()
    }

    private fun tryStart(start: () -> Unit): Boolean = try {
        start()
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Nothing to start", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to start", e)
        false
    } catch (e: IllegalStateException) {
        // Shortcuts of a locked profile.
        Log.w(TAG, "Can't start now", e)
        false
    }

    private companion object {
        const val TAG = "AppActions"
        const val MAX_MENU_SHORTCUTS = 5
    }
}
