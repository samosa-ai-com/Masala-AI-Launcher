package com.samosaai.masala.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.samosaai.masala.core.tool.ActionResult

/**
 * Opens system settings screens by name. Nothing is toggled by the tool itself: the user flips
 * the switch on the screen that opens, which is also why this needs no permission.
 */
class SettingTool(private val context: Context) {

    fun open(name: String?): ActionResult {
        val key = name?.trim()?.lowercase().orEmpty()
        val spec = spec(key)
            ?: return ActionResult.error("Unknown setting '$name'. Try one of: ${supported().joinToString(", ")}.")
        return try {
            val intent = Intent(spec.action).apply {
                // The app's own screen takes a package URI; notifications take the package extra
                // (its filter declares no data scheme, so a URI would never resolve).
                if (spec.needsPackage) data = Uri.fromParts("package", context.packageName, null)
                if (spec.needsPackageExtra) putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // No resolveActivity check: on Android 11+ it false-negatives without <queries>
            // entries, while startActivity still works; a missing handler throws below.
            context.startActivity(intent)
            ActionResult.ok("Opened ${spec.label}. The user changes it there; Masala can't flip it for them.")
        } catch (_: ActivityNotFoundException) {
            ActionResult.error("This phone has no ${spec.label} screen to open.")
        } catch (_: SecurityException) {
            ActionResult.error("The system refused to open ${spec.label}.")
        }
    }

    internal data class Spec(val action: String, val label: String, val needsPackage: Boolean = false, val needsPackageExtra: Boolean = false)

    internal companion object {
        fun supported(): List<String> = SPECS.keys.sorted()

        fun spec(name: String): Spec? = SPECS[name]

        private val SPECS = mapOf(
            // Panels (Android 10+): a floating sheet, not the full settings app.
            "wifi" to Spec(Settings.Panel.ACTION_WIFI, "the Wi-Fi panel"),
            "internet" to Spec(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "the internet panel"),
            "volume" to Spec(Settings.Panel.ACTION_VOLUME, "the volume panel"),
            "nfc" to Spec(Settings.Panel.ACTION_NFC, "the NFC panel"),
            // Full screens.
            "bluetooth" to Spec(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings"),
            "location" to Spec(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "location settings"),
            "notifications" to Spec(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS, "the app's notification settings", needsPackageExtra = true,
            ),
            "battery" to Spec(Settings.ACTION_BATTERY_SAVER_SETTINGS, "battery-saver settings"),
            "display" to Spec(Settings.ACTION_DISPLAY_SETTINGS, "display settings"),
            "sound" to Spec(Settings.ACTION_SOUND_SETTINGS, "sound settings"),
            "apps" to Spec(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "the app's info screen", needsPackage = true,
            ),
            "storage" to Spec(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "storage settings"),
            "language" to Spec(Settings.ACTION_LOCALE_SETTINGS, "language settings"),
            "date" to Spec(Settings.ACTION_DATE_SETTINGS, "date and time settings"),
            "airplane" to Spec(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "airplane-mode settings"),
            "accessibility" to Spec(Settings.ACTION_ACCESSIBILITY_SETTINGS, "accessibility settings"),
        )
    }
}
