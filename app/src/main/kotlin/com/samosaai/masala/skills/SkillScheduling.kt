package com.samosaai.masala.skills

import android.content.Context
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.scheduled.ScheduledTask
import com.samosaai.masala.core.scheduled.ScheduledTaskPlanner
import com.samosaai.masala.core.scheduled.ScheduledTaskStore
import com.samosaai.masala.core.scheduled.SkillSchedule
import com.samosaai.masala.core.scheduled.SkillScheduleParser
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Skills whose default schedule the user said no to, so the widget doesn't ask again. */
interface DeclinedSchedules {
    fun contains(skillId: String): Boolean
    fun add(skillId: String)
}

class PrefsDeclinedSchedules(context: Context) : DeclinedSchedules {
    private val prefs = context.getSharedPreferences("schedule_declined", Context.MODE_PRIVATE)
    override fun contains(skillId: String) = prefs.getBoolean(skillId, false)
    override fun add(skillId: String) = prefs.edit().putBoolean(skillId, true).apply()
}

/**
 * A skill widget and its schedule (plan Phase 4 M6): a skill with `schedule.default` gets a scheduled task
 * when its widget is first on home, but only after the user agrees, and not again once they've said no
 * or the task exists (even switched off).
 */
class SkillScheduling(private val store: ScheduledTaskStore, private val declined: DeclinedSchedules) {
    data class Offer(val skill: MasalaSkill, val schedule: SkillSchedule)

    /** When the skill's task runs next, if it has one that is on. */
    fun nextRunAt(skillId: String): Long? =
        store.all().filter { it.skillId == skillId && it.enabled }.mapNotNull { it.dueAt }.minOrNull()

    fun offerFor(skill: MasalaSkill, now: LocalDateTime): Offer? {
        val default = skill.schedule?.default ?: return null
        if (declined.contains(skill.id) || store.all().any { it.skillId == skill.id }) return null
        return SkillScheduleParser.parse(default, now)?.let { Offer(skill, it) }
    }

    /** Creates and arms the task. The caller re-arms the alarm. */
    fun accept(offer: Offer, nowMillis: Long, zone: ZoneId): ScheduledTask {
        val title = offer.skill.title.ifBlank { offer.skill.id }
        val task = ScheduledTaskPlanner.armed(
            ScheduledTask(
                id = "skill-${offer.skill.id}",
                name = title,
                prompt = "Scheduled run: do what your instructions say now, and reply with just the result.",
                startLocal = offer.schedule.startLocal,
                repeat = offer.schedule.repeat,
                skillId = offer.skill.id,
                createdAt = nowMillis,
            ),
            nowMillis,
            zone,
        )
        store.put(task)
        return task
    }

    fun decline(skillId: String) = declined.add(skillId)

    companion object {
        fun now(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = LocalDateTime.ofInstant(Instant.now(), zone)
    }
}
