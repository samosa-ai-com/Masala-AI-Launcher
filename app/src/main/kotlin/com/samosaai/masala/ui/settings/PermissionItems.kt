package com.samosaai.masala.ui.settings

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.samosaai.masala.R
import com.samosaai.masala.launcher.HomeRole
import com.samosaai.masala.masala
import com.samosaai.masala.tools.Capability
import com.samosaai.masala.tools.HealthPermissionState

data class PermissionGroup(
    @StringRes val name: Int,
    val items: List<PermissionItem>,
)

/**
 * One row on Settings → Privacy → Permissions. A row is either a runtime permission
 * ([androidPermission], shown as the system dialog) or a special access ([special], a system screen).
 */
data class PermissionItem(
    @StringRes val name: Int,
    @StringRes val description: Int,
    val androidPermission: String?,
    val special: Special?,
    val isGranted: (Context) -> Boolean,
    /** Requested in the same dialog as [androidPermission] (the write half of a read/write pair). */
    val extraPermissions: List<String> = emptyList(),
    /** Whether to show the row at all: all-files access only exists in builds with `:pro`. */
    val isRelevant: (Context) -> Boolean = { true },
)

/** Access granted on a system screen rather than through the runtime dialog. */
sealed interface Special {
    data class Of(val capability: Capability) : Special
    data object HealthConnect : Special
    data object DefaultHome : Special
}

/** Every permission and special access Masala can use. Only what the manifest declares. */
fun allPermissionGroups(): List<PermissionGroup> = listOf(
    PermissionGroup(
        R.string.permission_group_launcher,
        listOf(
            PermissionItem(
                R.string.permission_default_home,
                R.string.permission_default_home_description,
                null,
                Special.DefaultHome,
                HomeRole::isDefault,
            ),
        ),
    ),
    PermissionGroup(
        R.string.permission_group_notifications,
        listOf(
            PermissionItem(
                R.string.permission_show_notifications,
                R.string.permission_show_notifications_description,
                android.Manifest.permission.POST_NOTIFICATIONS,
                null,
                // Below Android 13 it is granted at install time; there is no dialog to show.
                { c ->
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        checkPerm(c, android.Manifest.permission.POST_NOTIFICATIONS)
                },
            ),
            special(Capability.NOTIFICATION_LISTENER, R.string.permission_notification_listener, R.string.permission_notification_listener_description),
        ),
    ),
    PermissionGroup(
        R.string.permission_group_contacts,
        listOf(runtime(android.Manifest.permission.READ_CONTACTS, R.string.permission_contacts, R.string.permission_contacts_description)),
    ),
    PermissionGroup(
        R.string.permission_group_calendar,
        listOf(
            PermissionItem(
                R.string.permission_calendar,
                R.string.permission_calendar_description,
                android.Manifest.permission.READ_CALENDAR,
                null,
                { c ->
                    checkPerm(c, android.Manifest.permission.READ_CALENDAR) &&
                        checkPerm(c, android.Manifest.permission.WRITE_CALENDAR)
                },
                extraPermissions = listOf(android.Manifest.permission.WRITE_CALENDAR),
            ),
        ),
    ),
    PermissionGroup(
        R.string.permission_group_media_storage,
        listOf(
            runtime(android.Manifest.permission.RECORD_AUDIO, R.string.permission_microphone, R.string.permission_microphone_description),
            special(Capability.ALL_FILES, R.string.permission_all_files_access, R.string.permission_all_files_access_description)
                .copy(isRelevant = { c -> c.masala.fileIndex != null }),
        ),
    ),
    PermissionGroup(
        R.string.permission_group_location,
        listOf(runtime(android.Manifest.permission.ACCESS_COARSE_LOCATION, R.string.permission_location, R.string.permission_location_description)),
    ),
    PermissionGroup(
        R.string.permission_group_nearby,
        listOf(runtime(android.Manifest.permission.BLUETOOTH_CONNECT, R.string.permission_bluetooth, R.string.permission_bluetooth_description)),
    ),
    PermissionGroup(
        R.string.permission_group_device_control,
        listOf(
            special(Capability.DND_ACCESS, R.string.permission_do_not_disturb, R.string.permission_do_not_disturb_description),
            special(Capability.USAGE_ACCESS, R.string.permission_usage_access, R.string.permission_usage_access_description),
            special(Capability.INSTALL_UNKNOWN_APPS, R.string.permission_install_apps, R.string.permission_install_apps_description),
        ),
    ),
    PermissionGroup(
        R.string.permission_group_health,
        listOf(
            PermissionItem(
                R.string.permission_health_connect,
                R.string.permission_health_connect_description,
                null,
                Special.HealthConnect,
                // Health Connect only reports grants from a suspend call, so this is the last check.
                { HealthPermissionState.isGranted() },
            ),
        ),
    ),
)

private fun runtime(permission: String, @StringRes name: Int, @StringRes description: Int) =
    PermissionItem(name, description, permission, null, { c -> checkPerm(c, permission) })

private fun special(capability: Capability, @StringRes name: Int, @StringRes description: Int) =
    PermissionItem(name, description, null, Special.Of(capability), capability::isAvailable)

private fun checkPerm(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
