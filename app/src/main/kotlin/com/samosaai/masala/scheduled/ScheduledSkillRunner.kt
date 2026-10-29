package com.samosaai.masala.scheduled

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.samosaai.masala.R
import com.samosaai.masala.core.scheduled.ScheduledTask
import com.samosaai.masala.jobs.JobRunner
import com.samosaai.masala.masala
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The [TaskRunner] (plan Phase 4 M6): a due task becomes a foreground [JobRunner] job. One scheduled run
 * at a time, so a second one while the first is going reports [ScheduledStart.Busy] and waits its turn.
 * The job reports how the run ended through [ScheduledTaskRuns.recordOutcome].
 */
class ScheduledSkillRunner(private val context: Context) : TaskRunner {
    override suspend fun start(task: ScheduledTask): ScheduledStart {
        val work = WorkManager.getInstance(context)
        val busy = withContext(Dispatchers.IO) { work.getWorkInfosForUniqueWork(UNIQUE).get().any { !it.state.isFinished } }
        if (busy) return ScheduledStart.Busy
        task.skillId?.let { id ->
            if (context.masala.skills.getSkillById(id) == null) {
                return ScheduledStart.Failed(context.getString(R.string.scheduled_skill_gone, id))
            }
        }
        val runId = UUID.randomUUID().toString()
        val request = OneTimeWorkRequestBuilder<JobRunner>()
            .setInputData(
                Data.Builder()
                    .putString(JobRunner.KEY_TASK_ID, task.id)
                    .putString(JobRunner.KEY_RUN_ID, runId)
                    .putString(JobRunner.KEY_NAME, task.name)
                    .putString(JobRunner.KEY_PROMPT, task.prompt)
                    .apply { task.skillId?.let { putString(JobRunner.KEY_SKILL_ID, it) } }
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        work.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
        return ScheduledStart.Started(runId)
    }

    private companion object {
        const val UNIQUE = "scheduled-run"
    }
}
