package com.samosaai.masala.tools

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.Settings

/**
 * From the private prototype's SystemTool: battery, the Wi-Fi panel and opening apps. Brightness and screen timeout
 * are left out; they need `WRITE_SETTINGS`, which isn't on the core allowlist.
 */
class SystemTool(private val context: Context) {

    fun getBatteryInfo(): ActionResult {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            // Temperature, voltage, health, and plugged info come from the sticky battery intent.
            val batteryIntent = context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val tempRaw = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val voltage = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val healthInt = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1

            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val pluggedLabel = when (plugged) {
                android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> null
            }
            val tempC = if (tempRaw > 0) tempRaw / 10f else null
            val healthLabel = when (healthInt) {
                android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
                android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over-voltage"
                android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                else -> null
            }
            val message = buildString {
                append("Battery at $percent%")
                if (charging) {
                    append(", charging")
                    if (pluggedLabel != null) append(" ($pluggedLabel)")
                } else {
                    append(", not charging")
                }
                if (tempC != null) append(", temp: ${"%.1f".format(tempC)}°C")
                if (voltage > 0) append(", voltage: ${voltage / 1000}V")
                if (healthLabel != null) append(", health: $healthLabel")
            }
            ActionResult.ok(message)
        } catch (e: Exception) {
            ActionResult.error("Could not read battery info: ${e.message}")
        }
    }

    /**
     * `setWifiEnabled` does nothing since Android 10, so this opens the system Wi-Fi panel where the
     * user flips it. [enabled] only shapes the message.
     */
    fun toggleWifi(enabled: Boolean): ActionResult {
        return try {
            context.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult.ok(
                "Opened the Wi-Fi panel. Android doesn't let apps switch Wi-Fi themselves, so ask the user to " +
                    "turn it ${if (enabled) "on" else "off"} there.",
            )
        } catch (e: Exception) {
            ActionResult.error("Could not open the Wi-Fi panel: ${e.message}")
        }
    }

    fun openApp(packageName: String): ActionResult {
        return try {
            val pm = context.packageManager
            val launch = pm.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                } catch (_: Exception) { packageName }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return ActionResult.ok("Launched $label.")
            }
            // Fuzzy fallback: match against installed app labels.
            val query = packageName.lowercase()
            val match = pm.getInstalledApplications(0).firstOrNull {
                pm.getApplicationLabel(it).toString().lowercase().contains(query) ||
                    it.packageName.lowercase().contains(query)
            }
            if (match != null) {
                val intent = pm.getLaunchIntentForPackage(match.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return ActionResult.ok(
                        "Launched ${pm.getApplicationLabel(match)} (${match.packageName})."
                    )
                }
            }
            ActionResult.error(
                "No launchable app found matching '$packageName'. You may use list_installed_apps to find the correct name or package name."
            )
        } catch (e: Exception) {
            ActionResult.error("Could not open app: ${e.message}")
        }
    }
}
