package com.samosaai.masala.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.apps.InstalledActivity
import com.samosaai.masala.core.apps.ProfileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every launcher activity in every profile, from `LauncherApps` (Unlauncher, Slim), kept current by
 * `LauncherApps.Callback` and the profile broadcasts. Apps are keyed on component + user serial, so
 * the personal and work copies of an app are separate entries.
 */
class AppCatalog(private val context: Context, private val scope: CoroutineScope) {

    data class Profile(val user: UserHandle, val serial: Long, val kind: ProfileKind, val paused: Boolean)

    data class Snapshot(
        val apps: List<InstalledActivity> = emptyList(),
        val profiles: List<Profile> = emptyList(),
        val loaded: Boolean = false,
    ) {
        val work get() = profiles.firstOrNull { it.kind == ProfileKind.WORK }
    }

    /** A package that was uninstalled from a profile, for pruning what the user set for it. */
    data class Removal(val packageName: String, val user: Long)

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val _removals = MutableSharedFlow<Removal>(extraBufferCapacity = 16)
    val removals: SharedFlow<Removal> = _removals

    /** Bumped when any app's shortcuts change, so pinned shortcuts are re-read. */
    private val _shortcutsVersion = MutableStateFlow(0)
    val shortcutsVersion: StateFlow<Int> = _shortcutsVersion.asStateFlow()

    @Volatile private var infos: Map<AppKey, LauncherActivityInfo> = emptyMap()
    private val reloads = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch { for (unused in reloads) load() }
        reload()
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        val profileEvents = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        }
        // System broadcasts only; nothing else can send these.
        ContextCompat.registerReceiver(context, profileReceiver, profileEvents, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun reload() {
        reloads.trySend(Unit)
    }

    fun info(key: AppKey): LauncherActivityInfo? = infos[key]

    fun user(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    fun serial(user: UserHandle): Long = userManager.getSerialNumberForUser(user)

    fun componentName(key: AppKey) = ComponentName(key.packageName, key.activity)

    /** System apps that were never updated can't be uninstalled, so the menu leaves that out. */
    fun canUninstall(key: AppKey): Boolean {
        val flags = info(key)?.applicationInfo?.flags ?: return false
        return flags and ApplicationInfo.FLAG_SYSTEM == 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }

    /**
     * Pauses or resumes work apps. Allowed for the default home app; Android may ask for the work
     * profile's lock first. Returns false when it wasn't allowed.
     */
    fun setWorkPaused(paused: Boolean): Boolean {
        val work = _state.value.work ?: return false
        return try {
            userManager.requestQuietModeEnabled(paused, work.user)
            true
        } catch (e: Exception) {
            // SecurityException when the profile refuses, but the framework can also answer with a
            // RemoteException wrapped in a Parcel exception; neither may take the drawer down.
            Log.w(TAG, "Can't change work profile quiet mode", e)
            false
        }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        val me = Process.myUserHandle()
        val profiles = userManager.userProfiles.mapNotNull { user ->
            if (isPrivateSpace(user)) return@mapNotNull null
            Profile(
                user = user,
                serial = userManager.getSerialNumberForUser(user),
                kind = if (user == me) ProfileKind.PERSONAL else ProfileKind.WORK,
                paused = user != me && userManager.isQuietModeEnabled(user),
            )
        }
        val found = HashMap<AppKey, LauncherActivityInfo>()
        val apps = profiles.flatMap { profile ->
            launcherApps.getActivityList(null, profile.user)
                .filter { it.componentName.packageName != context.packageName }
                .map { info ->
                    val key = AppKey(info.componentName.packageName, info.componentName.className, profile.serial)
                    found[key] = info
                    InstalledActivity(key, info.label.toString(), profile.kind)
                }
        }
        infos = found
        _state.value = Snapshot(apps, profiles, loaded = true)
    }

    /**
     * Android 15's private space is left out until it's handled properly (plan Phase 7 stretch): its
     * apps must not show in the normal drawer.
     */
    private fun isPrivateSpace(user: UserHandle): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            _removals.tryEmit(Removal(packageName, serial(user)))
            reload()
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = reload()
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = reload()

        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
            _shortcutsVersion.value++
        }
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reload()
            _shortcutsVersion.value++
        }
    }

    private companion object {
        const val TAG = "AppCatalog"
    }
}
