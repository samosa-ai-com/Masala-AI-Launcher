package com.samosaai.masala.scheduled

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.samosaai.masala.R
import com.samosaai.masala.jobs.JobNotifications
import com.samosaai.masala.ui.skills.SkillSheetActivity

/**
 * The persistent "run finished" notification (a run's answer otherwise lives only in
 * Settings). Tapping it opens the run's transcript; it clears on tap.
 */
object TaskDoneNotification {
    fun post(
        context: Context,
        name: String,
        skillId: String,
        skillRunId: Long,
        status: com.samosaai.masala.core.scheduled.TaskRunStatus,
        text: String?,
    ) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val body = when (status) {
            com.samosaai.masala.core.scheduled.TaskRunStatus.DONE ->
                text?.lineSequence()?.firstOrNull { it.isNotBlank() }
                    ?: context.getString(R.string.scheduled_task_run_done)
            com.samosaai.masala.core.scheduled.TaskRunStatus.FAILED ->
                text ?: context.getString(R.string.scheduled_task_run_failed)
            else -> context.getString(R.string.scheduled_task_run_stopped)
        }
        val open = PendingIntent.getActivity(
            context, skillRunId.hashCode(),
            SkillSheetActivity.intent(context, skillId, runId = skillRunId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, JobNotifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_masala)
            .setContentTitle(name)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One notification per run; the row id is unique per run.
        manager.notify(skillRunId.hashCode(), notification)
    }
}
