package com.samosaai.masala.core.tool


/**
 * What a tool copied from the private prototype returns: the private prototype's `ToolResult`, renamed so it doesn't clash with
 * `:core`'s [ToolResult]. [toToolResult] turns it into that at the registry boundary.
 *
 * @param needsPermission when non-null, the Android permission or special-access marker that must be
 *   granted before the tool can run.
 */
data class ActionResult(
    val success: Boolean,
    val message: String,
    val needsPermission: String? = null,
) {
    fun toToolResult(): ToolResult = when {
        needsPermission != null -> ToolResult.NeedsCapability(needsPermission, message)
        success -> ToolResult.Success(message)
        else -> ToolResult.Failure(message)
    }

    companion object {
        /** Marker for the "Usage access" special app access (app-usage stats). */
        const val USAGE_ACCESS = "special:usage_access"

        /** Marker for the "Do Not Disturb access" (notification-policy) special app access. */
        const val DND_ACCESS = "special:dnd_access"

        /** Marker for enabling the NotificationListenerService (Notification access). */
        const val NOTIFICATION_LISTENER_ACCESS = "special:notification_listener_access"

        /** Marker for the Health Connect read permissions, requested through Health Connect's own screen. */
        const val HEALTH_CONNECT = "special:health_connect"

        fun ok(message: String) = ActionResult(true, message)
        fun error(message: String) = ActionResult(false, message)
        fun permissionNeeded(permission: String, message: String) =
            ActionResult(false, message, needsPermission = permission)
    }
}
