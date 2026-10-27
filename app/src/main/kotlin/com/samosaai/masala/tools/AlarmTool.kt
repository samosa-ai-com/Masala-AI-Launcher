package com.samosaai.masala.tools

import com.samosaai.masala.core.tool.ActionResult
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Alarms and timers through the system clock app (`AlarmClock` intents), so they ring with its full
 * UI and show in its list. Adapted from the private prototype's AlarmTool without its in-app fallback: that one
 * schedules exact alarms, and `SCHEDULE_EXACT_ALARM` stays out of the core (arch §4). Without that
 * path there is no in-app list to edit or delete from either; the clock app owns its alarms.
 */
class AlarmTool(private val context: Context) {

    companion object {
        // Not a documented android.provider.AlarmClock constant; some clock apps
        // (e.g. Google Clock) honor it best-effort to dismiss a ringing timer.
        private const val ACTION_DISMISS_TIMER = "android.intent.action.DISMISS_TIMER"
    }

    private val dayNames = mapOf(
        "sun" to Calendar.SUNDAY, "sunday" to Calendar.SUNDAY,
        "mon" to Calendar.MONDAY, "monday" to Calendar.MONDAY,
        "tue" to Calendar.TUESDAY, "tuesday" to Calendar.TUESDAY,
        "wed" to Calendar.WEDNESDAY, "wednesday" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY, "thursday" to Calendar.THURSDAY,
        "fri" to Calendar.FRIDAY, "friday" to Calendar.FRIDAY,
        "sat" to Calendar.SATURDAY, "saturday" to Calendar.SATURDAY,
    )
    private val dayNumbers = dayNames.values.toSet()

    fun setAlarm(hour: Int, minute: Int, message: String? = null, days: List<String>? = null, vibrate: Boolean? = null): ActionResult {
        if (hour !in 0..23) return ActionResult.error("Hour must be 0-23.")
        if (minute !in 0..59) return ActionResult.error("Minute must be 0-59.")
        val dayInts = parseDays(days)
        val label = message?.takeIf { it.isNotBlank() }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (dayInts.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(dayInts))
            putExtra(AlarmClock.EXTRA_VIBRATE, vibrate ?: true)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        if (!dispatchClockIntent(intent)) return ActionResult.error("No clock app on this phone handles alarms.")
        val extra = buildString {
            if (dayInts.isNotEmpty()) append(" repeating (${days?.joinToString(",")})")
            if (vibrate == false) append(" silent")
        }
        return ActionResult.ok(
            "Set alarm${label?.let { " '$it'" } ?: ""} for %02d:%02d$extra in the clock app.".format(hour, minute),
        )
    }

    fun setTimer(seconds: Int, message: String? = null, hours: Int? = null, minutes: Int? = null): ActionResult {
        val totalSeconds = seconds + (hours ?: 0) * 3600 + (minutes ?: 0) * 60
        if (totalSeconds < 1) return ActionResult.error("Timer length must be at least 1 second.")
        val label = message?.takeIf { it.isNotBlank() }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
            if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        if (!dispatchClockIntent(intent)) return ActionResult.error("No clock app on this phone handles timers.")
        return ActionResult.ok("Started ${label ?: "a timer"} for ${totalSeconds}s in the clock app.")
    }

    /** Opens the system clock app's alarms list (ACTION_SHOW_ALARMS). */
    fun showAlarms(): ActionResult =
        if (dispatchClockIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS))) {
            ActionResult.ok("Opened the alarms list in the clock app.")
        } else {
            ActionResult.error("No clock app handled the show-alarms request.")
        }

    /** Snoozes the currently ringing alarm (ACTION_SNOOZE_ALARM). No effect if no alarm is ringing. */
    fun snoozeAlarm(minutes: Int? = null): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply {
            if (minutes != null && minutes > 0) putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, minutes)
        }
        return if (dispatchClockIntent(intent)) {
            ActionResult.ok(
                "Sent a snooze request to the clock app" + (minutes?.let { " for $it minute(s)" } ?: "") +
                    ". This only has an effect if an alarm is currently ringing.",
            )
        } else {
            ActionResult.error("No clock app handled the snooze request.")
        }
    }

    /** Dismisses a ringing clock-app timer (best-effort; not all clock apps support it). */
    fun dismissTimer(): ActionResult =
        if (dispatchClockIntent(Intent(ACTION_DISMISS_TIMER))) {
            ActionResult.ok(
                "Sent a dismiss request to the clock app. This only has an effect if a timer is " +
                    "currently ringing there; support varies by clock app.",
            )
        } else {
            ActionResult.error("No clock app handled the dismiss-timer request (not all clock apps support it).")
        }

    /** The next alarm on the phone, from any app: the only part of the clock app's list Android exposes. */
    fun nextAlarm(): ActionResult {
        val next = (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock
            ?: return ActionResult.ok("No alarm is set on this phone.")
        val time = SimpleDateFormat("EEE MMM d HH:mm", Locale.getDefault()).format(Date(next.triggerTime))
        val pkg = next.showIntent?.creatorPackage
        return ActionResult.ok("Next alarm: $time" + (if (pkg != null) " (set by $pkg)" else "") + ".")
    }

    /** Fires an AlarmClock intent at the clock app. Returns false if no app handles it. */
    private fun dispatchClockIntent(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun parseDays(days: List<String>?): List<Int> {
        if (days.isNullOrEmpty()) return emptyList()
        return days.flatMap { d ->
            when (val key = d.trim().lowercase()) {
                "weekdays" -> listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
                "weekend", "weekends" -> listOf(Calendar.SATURDAY, Calendar.SUNDAY)
                "daily", "everyday" -> dayNumbers.toList()
                else -> listOfNotNull(dayNames[key])
            }
        }.distinct()
    }
}
