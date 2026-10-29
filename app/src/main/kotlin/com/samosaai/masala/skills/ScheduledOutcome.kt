package com.samosaai.masala.skills

import com.samosaai.masala.core.scheduled.TaskRunStatus

/** How a skill run's saved status becomes the status a scheduled task records for that firing. */
object ScheduledOutcome {
    fun of(status: String?, error: String?): Pair<TaskRunStatus, String?> = when (status) {
        "done" -> TaskRunStatus.DONE to null
        "cancelled" -> TaskRunStatus.STOPPED to null
        else -> TaskRunStatus.FAILED to (error ?: "The run did not finish.")
    }
}
