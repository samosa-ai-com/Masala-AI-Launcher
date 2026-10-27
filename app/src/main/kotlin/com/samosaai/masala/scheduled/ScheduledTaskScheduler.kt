package com.samosaai.masala.scheduled

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.samosaai.masala.core.scheduled.ScheduledTaskPlanner
import com.samosaai.masala.masala

/**
 * From the private prototype's `ScheduledTaskScheduler`. Keeps one alarm armed for the scheduled task due soonest
 * (issue #132), and re-arms it after every change, every firing, and after a reboot, an app update or
 * a clock change (through [ScheduledTaskRebootReceiver]).
 *
 * Changed from the private prototype: the alarm is always inexact (`setAndAllowWhileIdle`). Masala doesn't hold
 * `SCHEDULE_EXACT_ALARM`, which Play allows only for alarm and calendar apps (arch §4), so a task
 * runs a few minutes late at worst. The "exact alarm needed to start the keep-alive service" branch
 * and the tap-to-run notification are gone with it; Phase 4's runner decides how to run in the
 * background.
 */
object ScheduledTaskScheduler {

    /** How soon [armHandoff]'s alarm fires when a pass was cut short. */
    const val HANDOFF_MS = 60L * 1000L

    private const val ALARM_REQUEST = 0
    private const val HANDOFF_REQUEST = 1

    /** Arms the alarm for the soonest task, or cancels it when nothing is due. Reads prefs: off the main thread. */
    fun reschedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = fireIntent(context)
        val at = ScheduledTaskPlanner.nextAlarm(context.masala.scheduledTasks.all())
        if (at == null) {
            alarm.cancel(pending)
            return
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    /**
     * Arms a second alarm a little ahead, for a firing whose work may outlive the receiver: if Android
     * kills the process before the pass records its runs and re-arms, this one fires and the pass is
     * made again. Cheap, as it reads nothing, so it is set before any of the slow work starts.
     */
    fun armHandoff(context: Context, now: Long = System.currentTimeMillis()) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + HANDOFF_MS, fireIntent(context, HANDOFF_REQUEST))
    }

    /** The pass finished: the handoff armed by [armHandoff] is not needed. */
    fun cancelHandoff(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(fireIntent(context, HANDOFF_REQUEST))
    }

    private fun fireIntent(context: Context, request: Int = ALARM_REQUEST): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, ScheduledTaskReceiver::class.java).setAction(ScheduledTaskReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
