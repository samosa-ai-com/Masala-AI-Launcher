package com.samosaai.masala.tools

import com.samosaai.masala.core.scheduled.Repeat
import com.samosaai.masala.core.scheduled.RepeatKind
import com.samosaai.masala.core.scheduled.ScheduledTask
import com.samosaai.masala.core.scheduled.ScheduledTaskPlanner
import com.samosaai.masala.core.scheduled.ScheduledTaskStore
import com.samosaai.masala.core.tool.ActionResult
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The agent's handle on its own reminders: schedule, list, edit and delete background tasks.
 * Validation and arming come from [ScheduledTaskPlanner] (the same rules the Settings editor
 * uses); [reschedule] re-arms the system alarm afterwards. Creating and editing ask the user
 * first (CHANGES_SOMETHING); deleting says what is lost (DESTRUCTIVE); listing is read-only.
 */
class ScheduledTaskTools(
    private val store: ScheduledTaskStore,
    private val reschedule: () -> Unit,
    private val skillIds: () -> Set<String> = { emptySet() },
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val readable = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.getDefault())

    fun schedule(
        name: String?,
        prompt: String?,
        start: String?,
        repeat: String? = null,
        interval: Int? = null,
        weekdays: List<String>? = null,
        skillId: String? = null,
        memoryNote: String? = null,
    ): ActionResult {
        if (name.isNullOrBlank()) return ActionResult.error("The task needs a name.")
        if (prompt.isNullOrBlank() && skillId == null) {
            return ActionResult.error("Say what the task should do (prompt), or pick a skill for it (skill_id).")
        }
        if (start.isNullOrBlank()) return ActionResult.error("Say when it first runs, e.g. '2026-11-02T08:00'.")
        val (parsed, parseError) = parseSchedule(start, repeat, interval, weekdays)
        if (parseError != null) return ActionResult.error(parseError)
        val (startLocal, rep) = parsed!!
        if (skillId != null && skillId !in skillIds()) return ActionResult.error("No installed skill '$skillId'.")
        val task = ScheduledTaskPlanner.armed(
            ScheduledTask(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                prompt = prompt?.trim().orEmpty(),
                startLocal = startLocal,
                repeat = rep,
                skillId = skillId,
                createdByAgent = true,
                createdAt = now(),
                memoryNote = memoryNote?.trim()?.takeIf { it.isNotEmpty() },
            ),
            now(), zone,
        )
        val problems = ScheduledTaskPlanner.problems(task, now(), zone)
        if (problems.isNotEmpty()) return ActionResult.error(problems.joinToString("; ") + ".")
        store.put(task)
        reschedule()
        return ActionResult.ok("Scheduled '${task.name}'${nextText(task)}. The user approved it; it runs even when the app is closed.")
    }

    fun list(): ActionResult {
        val tasks = store.all()
        if (tasks.isEmpty()) return ActionResult.ok("No scheduled tasks.")
        return ActionResult.ok(
            tasks.sortedBy { it.nextRunAt ?: Long.MAX_VALUE }.joinToString("\n") { t ->
                val next = t.nextRunAt?.let { "next ${readable.format(java.time.Instant.ofEpochMilli(it).atZone(zone))}" }
                    ?: "no run left"
                val state = if (!t.enabled) " [paused]" else ""
                "[${t.id}] ${t.name}$state — $next${if (t.repeat.kind != RepeatKind.NONE) ", repeats ${t.repeat.kind.name.lowercase()}" else ""}"
            },
        )
    }

    fun edit(
        taskId: String?,
        name: String? = null,
        prompt: String? = null,
        start: String? = null,
        repeat: String? = null,
        interval: Int? = null,
        weekdays: List<String>? = null,
        enabled: Boolean? = null,
        skillId: String? = null,
        memoryNote: String? = null,
        clearMemoryNote: Boolean = false,
    ): ActionResult {
        if (taskId.isNullOrBlank()) return ActionResult.error("Pass the task's id (list_scheduled_tasks shows them).")
        val current = store.get(taskId) ?: return ActionResult.error(
            "No scheduled task '$taskId'. It may already be deleted; list_scheduled_tasks shows what is there.",
        )
        var draft = current
        if (name != null) {
            if (name.isBlank()) return ActionResult.error("The name can't be blank.")
            draft = draft.copy(name = name.trim())
        }
        if (prompt != null) draft = draft.copy(prompt = prompt.trim())
        if (skillId != null) {
            if (skillId !in skillIds() && skillId.isNotBlank()) return ActionResult.error("No installed skill '$skillId'.")
            draft = draft.copy(skillId = skillId.takeIf { it.isNotBlank() })
        }
        if (start != null || repeat != null || interval != null || weekdays != null) {
            val (parsed, parseError) = parseSchedule(
                start ?: draft.startLocal, repeat, interval, weekdays, defaultRepeat = draft.repeat,
            )
            if (parseError != null) return ActionResult.error(parseError)
            draft = draft.copy(startLocal = parsed!!.first, repeat = parsed.second)
        }
        if (enabled != null) draft = draft.copy(enabled = enabled)
        if (memoryNote != null) draft = draft.copy(memoryNote = memoryNote.trim().takeIf { it.isNotEmpty() })
        if (clearMemoryNote) draft = draft.copy(memoryNote = null)
        val saved = ScheduledTaskPlanner.savedEdit(current, draft, now(), zone)
        val problems = ScheduledTaskPlanner.problems(saved, now(), zone)
        if (problems.isNotEmpty()) return ActionResult.error(problems.joinToString("; ") + ".")
        store.put(saved)
        reschedule()
        return ActionResult.ok("Updated '${saved.name}'${nextText(saved)}.")
    }

    fun delete(taskId: String?): ActionResult {
        if (taskId.isNullOrBlank()) return ActionResult.error("Pass the task's id (list_scheduled_tasks shows them).")
        val current = store.get(taskId) ?: return ActionResult.error("No scheduled task '$taskId'. Nothing to delete.")
        store.delete(taskId)
        reschedule()
        return ActionResult.ok("Deleted '${current.name}'. Its future runs are cancelled.")
    }

    private fun nextText(task: ScheduledTask): String = task.nextRunAt?.let {
        ", next run ${readable.format(java.time.Instant.ofEpochMilli(it).atZone(zone))}"
    }.orEmpty()

    internal companion object {
        /**
         * Parses the schedule half of the args: a start ("2026-11-02T08:00" or a date), a repeat
         * (none/hourly/daily/weekly/monthly), an every-N interval and weekday names or ISO numbers.
         * [defaultRepeat] fills repeat/interval/weekdays the caller didn't pass (edits).
         */
        fun parseSchedule(
            start: String,
            repeat: String?,
            interval: Int?,
            weekdays: List<String>?,
            defaultRepeat: Repeat = Repeat(),
        ): Pair<Pair<String, Repeat>?, String?> {
            val startLocal = parseStart(start) ?: return null to
                "Couldn't read the start '$start'. Use 2026-11-02T08:00, or a date for midnight."
            val kind = when (repeat?.trim()?.lowercase()) {
                null -> defaultRepeat.kind
                "none", "once" -> RepeatKind.NONE
                "hourly" -> RepeatKind.HOURLY
                "daily" -> RepeatKind.DAILY
                "weekly" -> RepeatKind.WEEKLY
                "monthly" -> RepeatKind.MONTHLY
                else -> return null to "Repeat must be none, hourly, daily, weekly or monthly, not '$repeat'."
            }
            val every = interval ?: defaultRepeat.interval
            if (every < 1) return null to "Repeat every at least 1."
            val days = when {
                weekdays != null -> parseWeekdays(weekdays) ?: return null to
                    "Weekdays are mon..sun (or 1..7, Monday first), e.g. ['mon','wed'] or ['weekdays']."
                // An edit that names the repeat without days keeps the stored set.
                repeat != null && kind == RepeatKind.WEEKLY && defaultRepeat.kind == RepeatKind.WEEKLY ->
                    defaultRepeat.weekdays
                repeat != null -> emptyList()
                else -> defaultRepeat.weekdays
            }
            if (kind == RepeatKind.WEEKLY && days.isEmpty()) return null to "Pick at least one weekday for a weekly task."
            return (startLocal to Repeat(kind, every, days)) to null
        }

        /** "2026-11-02T08:00", or a bare date meaning midnight. Kept as local text, never an instant. */
        fun parseStart(start: String): String? {
            val t = start.trim()
            val full = runCatching { LocalDateTime.parse(t) }.getOrNull()
            if (full != null) return full.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            val date = runCatching { java.time.LocalDate.parse(t) }.getOrNull() ?: return null
            return date.atStartOfDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        }

        /** Names ("mon", "weekdays", "weekend"), ISO numbers (1 = Monday) or a mix; null when junk. */
        fun parseWeekdays(raw: List<String>): List<Int>? {
            val out = mutableSetOf<Int>()
            for (entry in raw.flatMap { it.split(',', ';', ' ') }.map { it.trim().lowercase() }) {
                when (entry) {
                    "", "and" -> Unit
                    "mon", "monday", "1" -> out += 1
                    "tue", "tues", "tuesday", "2" -> out += 2
                    "wed", "wednesday", "3" -> out += 3
                    "thu", "thur", "thurs", "thursday", "4" -> out += 4
                    "fri", "friday", "5" -> out += 5
                    "sat", "saturday", "6" -> out += 6
                    "sun", "sunday", "7" -> out += 7
                    "weekdays" -> out += setOf(1, 2, 3, 4, 5)
                    "weekend", "weekends" -> out += setOf(6, 7)
                    "daily", "everyday" -> out += setOf(1, 2, 3, 4, 5, 6, 7)
                    else -> return null
                }
            }
            if (out.isEmpty()) return null
            return out.sorted()
        }
    }
}
