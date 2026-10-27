package com.samosaai.masala.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notification access, once the user grants it: what NotificationTool reads and dismisses, and the
 * media sessions Android only hands to an enabled listener. From the private prototype's
 * the private prototypeNotificationListenerService without its proactive entity detection and OTP copying. The
 * count is for the context service (Phase 2) and the quiet-nights urgent filter (Phase 6).
 */
class MasalaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        updateCount()
    }

    override fun onListenerDisconnected() {
        instance = null
        _count.value = 0
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = updateCount()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = updateCount()

    /** Snapshot of the currently-active notifications (safe wrapper around the platform call). */
    fun currentNotifications(): Array<StatusBarNotification> =
        try {
            activeNotifications ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }

    /** Dismiss a single notification by its platform key. */
    fun dismiss(key: String) = cancelNotification(key)

    /** Dismiss all dismissible notifications. */
    fun dismissAll() = cancelAllNotifications()

    private fun updateCount() {
        _count.value = currentNotifications().count { it.isClearable && it.packageName != packageName }
    }

    companion object {
        /** The live service while the user has granted access; null otherwise. */
        @Volatile
        var instance: MasalaNotificationListener? = null
            private set

        private val _count = MutableStateFlow(0)

        /** Clearable notifications from other apps; 0 without access. */
        val count: StateFlow<Int> = _count.asStateFlow()
    }
}
