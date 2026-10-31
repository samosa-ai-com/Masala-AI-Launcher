package com.samosaai.masala.jobs

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.core.scheduled.TaskRunStatus
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.scheduled.ScheduledTaskRuns
import com.samosaai.masala.skills.ScheduledOutcome
import com.samosaai.masala.skills.SkillRunEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * Runs a scheduled skill (or command) in the foreground with a progress notification, so a run that
 * takes minutes isn't killed with the process (plan Phase 4 M6). The alarm only enqueues this; the run
 * is `SkillRunner`'s, so the widget sees its result, and the outcome goes back to the scheduled task.
 * Long media jobs reuse it with [KEY_MEDIA].
 */
class JobRunner(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = info(inputData.getString(KEY_NAME).orEmpty(), "")

    override suspend fun doWork(): Result {
        val app = applicationContext as MasalaApp
        val taskId = inputData.getString(KEY_TASK_ID)
        val runId = inputData.getString(KEY_RUN_ID).orEmpty()
        val name = inputData.getString(KEY_NAME).orEmpty()
        val skillId = inputData.getString(KEY_SKILL_ID) ?: MasalaApp.COMMAND_SKILL_ID
        val prompt = inputData.getString(KEY_PROMPT).orEmpty()

        // Android may refuse to start a foreground service from the background; the run still goes ahead.
        runCatching { setForeground(info(name, "")) }.onFailure { Log.w(TAG, "No foreground service: ${it.message}") }

        suspend fun record(status: TaskRunStatus, detail: String?) {
            if (taskId != null) withContext(NonCancellable) { ScheduledTaskRuns(app).recordOutcome(taskId, runId, status, detail) }
        }

        val id = app.skillRunner.start(skillId, ChatMessage("user", JsonPrimitive(prompt)), unattended = true)
        if (id == null) {
            record(TaskRunStatus.FAILED, "The skill '$skillId' is not installed.")
            return Result.failure()
        }
        val state = app.skillRunner.open(id)!!
        try {
            coroutineScope {
                val progress = launch {
                    state.collect { s ->
                        val step = s.events.lastOrNull { it is SkillRunEvent.ToolStep } as? SkillRunEvent.ToolStep
                        runCatching { setForeground(info(name, step?.name?.let { "Running $it" }.orEmpty())) }
                    }
                }
                state.first { !it.running }
                progress.cancel()
            }
        } catch (e: CancellationException) {
            app.skillRunner.stop(id)
            record(TaskRunStatus.STOPPED, null)
            throw e
        }
        val (status, detail) = app.skillRunner.outcome(id)?.let { ScheduledOutcome.of(it.first, it.second) } ?: (TaskRunStatus.FAILED to "The run was lost.")
        record(status, detail)
        if (skillId == com.samosaai.masala.brief.BriefInputs.SKILL_ID && status == TaskRunStatus.DONE && app.settings.current().briefNotify) {
            app.database.skillRuns().get(id)?.let { com.samosaai.masala.skills.SkillRunner.lastAnswer(it.transcriptJson) }?.let { com.samosaai.masala.brief.BriefNotification.post(app, it) }
        }
        return Result.success()
    }

    private fun info(title: String, text: String) =
        JobNotifications.foregroundInfo(applicationContext, title.ifBlank { applicationContext.getString(com.samosaai.masala.R.string.jobs_running) }, text, inputData.getBoolean(KEY_MEDIA, false))

    companion object {
        private const val TAG = "JobRunner"
        const val KEY_TASK_ID = "taskId"
        const val KEY_RUN_ID = "runId"
        const val KEY_NAME = "name"
        const val KEY_SKILL_ID = "skillId"
        const val KEY_PROMPT = "prompt"
        const val KEY_MEDIA = "media"
    }
}
