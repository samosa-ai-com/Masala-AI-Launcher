package com.samosaai.masala.scheduled

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import com.samosaai.masala.R
import com.samosaai.masala.core.scheduled.ScheduledTask
import com.samosaai.masala.core.scheduled.ScheduledTaskPlanner
import com.samosaai.masala.core.scheduled.ScheduledTaskStore
import com.samosaai.masala.core.scheduled.TaskRunStatus
import com.samosaai.masala.masala
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** What [TaskRunner.start] made of a task. */
sealed class ScheduledStart {
    data class Started(val runId: String) : ScheduledStart()

    /** Another run holds the agent; nothing was started. */
    object Busy : ScheduledStart()

    data class Failed(val detail: String) : ScheduledStart()
}

/**
 * Starts the agent run of a scheduled task. the private prototype calls its `ChatRunner` here; Masala's runner is
 * `MasalaAgent`'s skill runner (Phase 4), which reports back through
 * [ScheduledTaskRuns.recordOutcome]. Until then `MasalaApp.taskRunner` is null and tasks are recorded
 * as not started.
 */
fun interface TaskRunner {
    suspend fun start(task: ScheduledTask): ScheduledStart
}

/**
 * From the private prototype's `ScheduledTaskRuns`. Starts the runs of scheduled tasks and writes down how they went
 * (issue #132). One lock covers both, so a run that ends at once can't have its result written before
 * the record that it started. Without the private prototype's exact-alarm gate: every due task goes to the runner.
 */
class ScheduledTaskRuns(private val app: Application) {

    private val store: ScheduledTaskStore get() = app.masala.scheduledTasks

    /** Starts every task that is due now, records the rest of what happened, and re-arms the alarm. */
    suspend fun fireDue(now: Long = System.currentTimeMillis()) {
        lock.withLock {
            val zone = ZoneId.systemDefault()
            for (task in store.all().sortedBy { it.dueAt ?: Long.MAX_VALUE }) {
                when (val decision = ScheduledTaskPlanner.decide(task, now, zone)) {
                    ScheduledTaskPlanner.Decision.Wait -> Unit
                    is ScheduledTaskPlanner.Decision.Missed -> store.put(decision.task)
                    // The first due task takes the runner; the ones after it find it
                    // busy and wait their turn.
                    ScheduledTaskPlanner.Decision.Run -> start(task, now, zone, userInitiated = false)
                }
            }
        }
        ScheduledTaskScheduler.reschedule(app)
    }

    /**
     * Runs task [id] now, for "Run now". The task's schedule is left as it is, unless this was its
     * scheduled time.
     */
    suspend fun runNow(id: String, now: Long = System.currentTimeMillis()): ScheduledStart {
        val result = lock.withLock {
            val task = store.get(id) ?: return@withLock ScheduledStart.Failed(app.getString(R.string.scheduled_task_gone))
            start(task, now, ZoneId.systemDefault(), userInitiated = true)
        }
        ScheduledTaskScheduler.reschedule(app)
        return result
    }

    /**
     * Saves the editor's [draft]: a new task as it is, an existing one onto what is stored now, under the
     * same lock as firing and recording, so neither a run's result nor a firing is overwritten. An edit
     * to a task deleted meanwhile saves nothing.
     */
    suspend fun saveEdit(draft: ScheduledTask, isNew: Boolean, now: Long = System.currentTimeMillis()) {
        lock.withLock {
            val zone = ZoneId.systemDefault()
            if (isNew) store.put(ScheduledTaskPlanner.edited(null, draft, now, zone))
            else store.update(draft.id) { ScheduledTaskPlanner.savedEdit(it, draft, now, zone) }
        }
        ScheduledTaskScheduler.reschedule(app)
    }

    /** Ids of tasks with a run currently going, for the Stop button. */
    suspend fun runningTaskIds(): Set<String> = withContext(Dispatchers.IO) {
        val work = WorkManager.getInstance(app)
        work.getWorkInfosForUniqueWork(ScheduledSkillRunner.UNIQUE).get()
            .filterNot { it.state.isFinished }
            .flatMap { info -> info.tags.filter { it.startsWith(ScheduledSkillRunner.TASK_TAG) } }
            .map { it.removePrefix(ScheduledSkillRunner.TASK_TAG) }
            .toSet()
    }

    /**
     * Stops the in-flight run of [taskId]; true when one was going. The worker records
     * STOPPED itself as it is cancelled.
     */
    suspend fun stop(taskId: String): Boolean = withContext(Dispatchers.IO) {
        val work = WorkManager.getInstance(app)
        val going = work.getWorkInfosForUniqueWork(ScheduledSkillRunner.UNIQUE).get()
            .any { !it.state.isFinished && it.tags.contains(ScheduledSkillRunner.TASK_TAG + taskId) }
        if (going) work.cancelUniqueWork(ScheduledSkillRunner.UNIQUE)
        going
    }

    /** The run [runId] of task [taskId] ended with [status]; [detail] is its error, if any. */
    suspend fun recordOutcome(
        taskId: String,
        runId: String,
        status: TaskRunStatus,
        detail: String?,
        now: Long = System.currentTimeMillis(),
        skillRunId: Long? = null
    ) {
        lock.withLock {
            store.update(taskId) { ScheduledTaskPlanner.finished(it, status, runId, detail, now, skillRunId) }
        }
        ScheduledTaskScheduler.reschedule(app)
    }

    private suspend fun start(
        task: ScheduledTask,
        now: Long,
        zone: ZoneId,
        userInitiated: Boolean
    ): ScheduledStart {
        val runner = app.masala.taskRunner
        val result = if (runner == null) {
            ScheduledStart.Failed(app.getString(R.string.scheduled_task_no_runner))
        } else {
            withContext(Dispatchers.Main) { runner.start(task) }
        }
        when (result) {
            is ScheduledStart.Started ->
                store.update(task.id) { ScheduledTaskPlanner.started(it, now, zone) }
            ScheduledStart.Busy ->
                if (!userInitiated) store.update(task.id) { ScheduledTaskPlanner.busy(it, now, zone) }
            is ScheduledStart.Failed ->
                store.update(task.id) {
                    ScheduledTaskPlanner.notStarted(it, TaskRunStatus.NOT_STARTED, result.detail, now, zone)
                }
        }
        return result
    }

    private companion object {
        val lock = Mutex()
    }
}

/**
 * The scheduled-task alarm going off. Not exported: only Masala's own PendingIntent fires it.
 *
 * The pass (reading the store, starting the runner, for every due task) can outlast a broadcast's
 * time on a cold start, and Android may then kill the process before the starts are recorded or the
 * alarm re-armed. So a handoff alarm is armed first, before any of that work, and cleared once the
 * pass is done; a pass cut short is made again when it fires. Making it again is safe: a task whose
 * start was recorded is no longer due, and one whose start was not had its run killed with the process.
 */
class ScheduledTaskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as Application
        if (intent.action != ACTION_FIRE) return
        ScheduledTaskScheduler.armHandoff(app)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ScheduledTaskRuns(app).fireDue()
            } catch (e: Exception) {
                Log.w(TAG, "Scheduled task work failed", e)
                // fireDue re-arms only when it finishes; without this the next task would never fire.
                runCatching { ScheduledTaskScheduler.reschedule(app) }
            } finally {
                ScheduledTaskScheduler.cancelHandoff(app)
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ScheduledTasks"
        const val ACTION_FIRE = "com.samosaai.masala.scheduled.FIRE"
    }
}

/**
 * Re-arms the scheduled-task alarm after a reboot, an app update or a clock change, none of which an
 * alarm survives at the right time. From the private prototype's `NotificationRescheduleReceiver`, without the
 * notification alarms and the exact-alarm broadcast. Exported only because those broadcasts come from
 * the system; it never runs a task itself.
 */
class ScheduledTaskRebootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ScheduledTaskScheduler.reschedule(app)
                // Alarms die with a reboot or a clock change; deadlines live on in widget state.
                com.samosaai.masala.widgets.WidgetTimers.rearmAll(app)
            } catch (e: Exception) {
                Log.w(TAG, "Re-arming scheduled tasks failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ScheduledTasks"
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}
