package com.samosaai.masala.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notification access, once the user grants it: what NotificationTool reads and dismisses, and the
 * media sessions Android only hands to an enabled listener. Adapted from the earlier prototype's
 * notification listener service, without its proactive entity detection and OTP copying. The
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
        _urgent.value = 0
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
        val others = currentNotifications().filter { it.isClearable && it.packageName != packageName }
        _count.value = others.size
        _urgent.value = others.count { it.notification.category in URGENT_CATEGORIES }
    }

    companion object {
        /** The live service while the user has granted access; null otherwise. */
        @Volatile
        var instance: MasalaNotificationListener? = null
            private set

        private val _count = MutableStateFlow(0)

        /** Clearable notifications from other apps; 0 without access. */
        val count: StateFlow<Int> = _count.asStateFlow()

        private val _urgent = MutableStateFlow(0)

        /**
         * Calls, messages, alarms, reminders and events among them: the `UrgentCount` condition's
         * signal until the urgent filter (starred contacts, wiki people, keywords) exists.
         */
        val urgent: StateFlow<Int> = _urgent.asStateFlow()

        private val URGENT_CATEGORIES = setOf(
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_MESSAGE,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_REMINDER,
            Notification.CATEGORY_EVENT,
        )
    }
}
