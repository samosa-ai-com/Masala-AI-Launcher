package com.samosaai.masala.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.scheduled.Repeat
import com.samosaai.masala.core.scheduled.RepeatKind
import com.samosaai.masala.core.scheduled.ScheduledTask
import com.samosaai.masala.core.scheduled.ScheduledTaskPlanner
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.UUID

/**
 * Creates a task, or edits [existing]. The live line shows the next run, or the first
 * [ScheduledTaskPlanner.problems] problem; Save stays off while there are problems.
 * [onSave] gets the draft; the caller re-arms it with [ScheduledTaskPlanner.edited].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditorSheet(
    existing: ScheduledTask?,
    skills: List<MasalaSkill>,
    onSave: (ScheduledTask) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    // Fixed while the sheet is open, so the draft id and the live line stay put.
    val newId = remember { UUID.randomUUID().toString() }
    val createdAt = remember { System.currentTimeMillis() }
    val startOf = { t: ScheduledTask ->
        runCatching { LocalDateTime.parse(t.startLocal) }.getOrNull()
    }
    val firstStart = existing?.let(startOf) ?: LocalDateTime.now().plusHours(1)

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var prompt by remember { mutableStateOf(existing?.prompt ?: "") }
    var skillId by remember { mutableStateOf(existing?.skillId) }
    var date by remember { mutableStateOf(firstStart.toLocalDate()) }
    var time by remember { mutableStateOf(firstStart.toLocalTime().withSecond(0).withNano(0)) }
    var kind by remember { mutableStateOf(existing?.repeat?.kind ?: RepeatKind.NONE) }
    var interval by remember { mutableStateOf(existing?.repeat?.interval?.coerceIn(1, MAX_STEP) ?: 1) }
    var weekdays by remember { mutableStateOf(existing?.repeat?.weekdays.orEmpty().toSet()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var maxRetries by remember { mutableStateOf(existing?.maxRetries ?: ScheduledTask.DEFAULT_MAX_RETRIES) }
    var retryDelay by remember { mutableStateOf(existing?.retryDelayMinutes ?: ScheduledTask.DEFAULT_RETRY_DELAY_MINUTES) }
    var advanced by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var skillMenu by remember { mutableStateOf(false) }

    val startLocal = "%04d-%02d-%02dT%02d:%02d".format(date.year, date.monthValue, date.dayOfMonth, time.hour, time.minute)
    val base = existing ?: ScheduledTask(
        id = newId, name = "", prompt = "", startLocal = startLocal,
        createdAt = createdAt, createdByAgent = false,
    )
    val draft = base.copy(
        name = name,
        prompt = prompt,
        skillId = skillId,
        startLocal = startLocal,
        repeat = Repeat(kind = kind, interval = interval, weekdays = weekdays.sorted()),
        enabled = enabled,
        maxRetries = maxRetries,
        retryDelayMinutes = retryDelay,
    )
    // Fresh on every field change; close enough to ticking for a live line.
    val now = System.currentTimeMillis()
    val problems = remember(draft) { ScheduledTaskPlanner.problems(draft, now, zone) }
    val nextAt = remember(draft) { ScheduledTaskPlanner.armed(draft, now, zone).nextRunAt }
    val dateFormat = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (existing == null) R.string.scheduled_task_new_title else R.string.scheduled_task_edit_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                name, { name = it },
                label = { Text(stringResource(R.string.scheduled_task_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                prompt, { prompt = it },
                label = { Text(stringResource(R.string.scheduled_task_prompt)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.scheduled_task_skill), style = MaterialTheme.typography.titleSmall)
            val selected = skills.firstOrNull { it.id == skillId }
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { skillMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selected?.title?.ifBlank { selected.id } ?: stringResource(R.string.scheduled_task_no_skill))
                }
                DropdownMenu(expanded = skillMenu, onDismissRequest = { skillMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scheduled_task_no_skill)) },
                        onClick = { skillId = null; skillMenu = false },
                    )
                    skills.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.title.ifBlank { s.id }) },
                            onClick = { skillId = s.id; skillMenu = false },
                        )
                    }
                }
            }
            Text(stringResource(R.string.scheduled_task_starts), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val day = remember(date) {
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(
                        java.util.Date.from(date.atStartOfDay(zone).toInstant())
                    )
                }
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) { Text(day) }
                OutlinedButton(
                    onClick = { showTime = true },
                    modifier = Modifier.weight(1f),
                ) { Text("%02d:%02d".format(time.hour, time.minute)) }
            }
            Text(stringResource(R.string.scheduled_task_repeat), style = MaterialTheme.typography.titleSmall)
            val kinds = listOf(
                RepeatKind.NONE to stringResource(R.string.scheduled_task_once),
                RepeatKind.HOURLY to stringResource(R.string.scheduled_task_hourly),
                RepeatKind.DAILY to stringResource(R.string.scheduled_task_daily),
                RepeatKind.WEEKLY to stringResource(R.string.scheduled_task_weekly),
                RepeatKind.MONTHLY to stringResource(R.string.scheduled_task_monthly),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { i, (k, label) ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = kinds.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
            if (kind == RepeatKind.HOURLY || kind == RepeatKind.DAILY) {
                Stepper(
                    if (kind == RepeatKind.HOURLY) stringResource(R.string.scheduled_task_every_hours, interval)
                    else stringResource(R.string.scheduled_task_every_days, interval),
                    interval, 1..MAX_STEP,
                ) { interval = it }
            }
            if (kind == RepeatKind.WEEKLY) {
                Text(stringResource(R.string.scheduled_task_days), style = MaterialTheme.typography.titleSmall)
                val locale = LocalLocale.current.platformLocale
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..7).forEach { d ->
                        FilterChip(
                            selected = d in weekdays,
                            onClick = { weekdays = if (d in weekdays) weekdays - d else weekdays + d },
                            label = {
                                Text(DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, locale))
                            },
                        )
                    }
                }
            }
            SettingsToggleRow(stringResource(R.string.scheduled_task_enabled), enabled) { enabled = it }
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.scheduled_task_advanced)) }
            if (advanced) {
                Stepper(stringResource(R.string.scheduled_task_retries), maxRetries, 0..ScheduledTask.MAX_RETRIES) {
                    maxRetries = it
                }
                Stepper(stringResource(R.string.scheduled_task_retry_delay, retryDelay), retryDelay, 1..MAX_DELAY_MIN) {
                    retryDelay = it
                }
            }
            if (problems.isNotEmpty()) {
                Text(problems.first(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    stringResource(R.string.scheduled_task_next_run, dateFormat.format(java.util.Date(nextAt!!))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(onClick = { onSave(draft) }, enabled = problems.isEmpty(), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    showDate = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) { DatePicker(state) }
    }
    if (showTime) {
        val state = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(state.hour, state.minute)
                    showTime = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            text = { TimeInput(state) },
        )
    }
}

/** A label with − / + steppers, keeping the value in [range]. */
@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onValue((value - 1).coerceIn(range)) }, enabled = value > range.first) {
                Text(STEP_DOWN)
            }
            Text("$value", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { onValue((value + 1).coerceIn(range)) }, enabled = value < range.last) {
                Text(STEP_UP)
            }
        }
    }
}

/** A label with − / + steppers, keeping the value in [range]. */
private const val MAX_STEP = 30
private const val MAX_DELAY_MIN = 60
private const val STEP_DOWN = "−"
private const val STEP_UP = "+"
