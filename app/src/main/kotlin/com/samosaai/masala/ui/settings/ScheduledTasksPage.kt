package com.samosaai.masala.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.scheduled.RepeatKind
import com.samosaai.masala.core.scheduled.ScheduledTask
import com.samosaai.masala.core.scheduled.ScheduledTaskPlanner
import com.samosaai.masala.masala
import com.samosaai.masala.scheduled.ScheduledTaskRuns
import com.samosaai.masala.scheduled.ScheduledTaskScheduler
import kotlinx.coroutines.launch

/** Every scheduled task in one place, so runs can be fired, paused or deleted (plan F5). */
@Composable
fun ScheduledTasksPage(onBack: () -> Unit, onAskInChat: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.masala
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(listOf<ScheduledTask>()) }
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    suspend fun sync() {
        tasks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.scheduledTasks.all() }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { sync() }
    fun mutate(block: suspend () -> Unit) {
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                block()
                ScheduledTaskScheduler.reschedule(context)
            }
            sync()
        }
    }
    SettingsScaffold(stringResource(R.string.settings_scheduled_tasks), onBack = onBack) {
        val createPrefill = stringResource(R.string.scheduled_task_create_prefill)
        Button(onClick = { onAskInChat(createPrefill) }) { Text(stringResource(R.string.scheduled_task_create)) }
        var filter by rememberSaveable { mutableStateOf("") }
        if (tasks.size > 5 || filter.isNotEmpty()) ListSearchField(filter, { filter = it }, stringResource(R.string.scheduled_tasks_filter))
        val shown = remember(tasks, filter) {
            val q = filter.trim().lowercase()
            if (q.isEmpty()) tasks else tasks.filter {
                it.name.lowercase().contains(q) || it.prompt.lowercase().contains(q)
            }
        }
        if (tasks.isEmpty()) {
            Text(stringResource(R.string.scheduled_tasks_empty), style = MaterialTheme.typography.bodyMedium)
        } else if (shown.isEmpty()) {
            Text(stringResource(R.string.scheduled_tasks_empty_no_match), style = MaterialTheme.typography.bodyMedium)
        }
        shown.forEach { task ->
            key(task.id) {
                var confirmDelete by remember(task.id) { mutableStateOf(false) }
                // Paused tasks still show their scheduled time; a finished one-time task has nothing to pause.
                val next = (task.retryAt ?: task.nextRunAt)?.let { format.format(java.util.Date(it)) }
                val canPause = task.repeat.kind != RepeatKind.NONE || task.nextRunAt != null
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (!task.enabled) {
                            next?.let { stringResource(R.string.scheduled_task_paused_next, it) }
                                ?: stringResource(R.string.scheduled_task_paused)
                        } else {
                            next?.let { stringResource(R.string.scheduled_task_next, it) }
                                ?: stringResource(R.string.scheduled_task_done)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    task.memoryNote?.let { note ->
                        Text(
                            stringResource(R.string.scheduled_task_note, note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    task.history.lastOrNull()?.let { last ->
                        Text(
                            stringResource(R.string.scheduled_task_last, last.status.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    ScheduledTaskRuns(context.applicationContext as android.app.Application).runNow(task.id)
                                }
                                sync()
                            }
                        }) { Text(stringResource(R.string.scheduled_task_run_now)) }
                        TextButton(
                            enabled = canPause,
                            onClick = {
                                mutate {
                                    app.scheduledTasks.update(task.id) {
                                        ScheduledTaskPlanner.toggled(
                                            it, !it.enabled,
                                            System.currentTimeMillis(), java.time.ZoneId.systemDefault(),
                                        )
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(if (task.enabled) R.string.scheduled_task_pause else R.string.scheduled_task_resume))
                        }
                        TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.scheduled_task_delete)) }
                    }
                }
                HorizontalDivider()
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text(stringResource(R.string.scheduled_task_delete_title)) },
                        text = { Text(stringResource(R.string.scheduled_task_delete_message, task.name)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDelete = false
                                mutate {
                                    app.taskNoteLinks.taskDeleted(task)
                                    app.scheduledTasks.delete(task.id)
                                }
                            }) { Text(stringResource(R.string.scheduled_task_delete)) }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_back)) } },
                    )
                }
            }
        }
    }
}
