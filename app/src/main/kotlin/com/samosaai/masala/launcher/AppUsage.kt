package com.samosaai.masala.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.apps.UsageRank
import com.samosaai.masala.data.AppLaunchDao
import com.samosaai.masala.data.AppLaunchEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How often each app is opened (plan UI refresh U3): Masala's own launch log (home, drawer, search,
 * the agent), kept 30 days, or, with Usage access granted (decision 11), the system's foreground events,
 * which also count launches from outside Masala. Feeds the globe drawer and "Suggested now".
 */
class AppUsage(private val context: Context, private val dao: AppLaunchDao, private val scope: CoroutineScope) {
    fun record(key: AppKey) {
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.insert(AppLaunchEntity(appKey = key.id, at = now))
                if (now % 20 == 0L) dao.prune(now - KEEP_MS)
            }
        }
    }

    /** Usage access is a special app-op the user grants in system settings. */
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Launches since [since], as (app key id, time). From the system when allowed (each time an app comes to
     * the foreground from another), mapped onto [apps] by package for the personal profile; else the log.
     */
    suspend fun launches(since: Long, apps: List<AppEntry>): List<UsageRank.Launch> = withContext(Dispatchers.IO) {
        if (hasUsageAccess()) systemLaunches(since, apps)?.let { return@withContext it }
        dao.since(since).map { UsageRank.Launch(it.appKey, it.at) }
    }

    private fun systemLaunches(since: Long, apps: List<AppEntry>): List<UsageRank.Launch>? = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val me = Process.myUserHandle().hashCode().toLong()
        val byPackage = apps.filter { it.key.user == me }.groupBy { it.key.packageName }.mapValues { it.value.first().key.id }
        val events = usm.queryEvents(since, System.currentTimeMillis())
        val out = ArrayList<UsageRank.Launch>()
        val e = UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            // A new app in front counts once; moving between its own screens doesn't.
            if (e.packageName != foreground) {
                foreground = e.packageName
                byPackage[e.packageName]?.let { out += UsageRank.Launch(it, e.timeStamp) }
            }
        }
        out
    }.getOrNull()

    companion object {
        private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}
