package com.samosaai.masala.miniapps

import android.os.SystemClock

/**
 * Dedupes the create/update auto-open. Two paths open a fresh app: the agent's
 * `open_mini_app` tool during the run, and the reply effect in `HomeActivity`
 * when the run ends with a mini-app result. Both firing stacked two identical
 * activities, so Back from the app revealed the same app again. Every opener
 * notes itself here; the effect skips an app that is already open.
 *
 * Notes are per app id and expire on their own; closing the viewer clears its
 * note at once, so reopening after Back always works. In-memory only: a process
 * restart starts with nothing noted, which can only ever open, never swallow.
 */
object MiniAppOpenGuard {
    /** Covers tool-open → run-end → reply, even with repair turns in between. */
    const val WINDOW_MS = 120_000L

    private val openedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    internal var clock: () -> Long = SystemClock::uptimeMillis

    fun noteOpened(id: String) {
        if (id.isNotEmpty()) openedAt[id] = clock()
    }

    fun clear(id: String) {
        openedAt.remove(id)
    }

    fun isFreshlyOpened(id: String): Boolean {
        val at = openedAt[id] ?: return false
        if (clock() - at > WINDOW_MS) {
            openedAt.remove(id)
            return false
        }
        return true
    }
}
