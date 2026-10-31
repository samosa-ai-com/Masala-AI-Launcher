package com.samosaai.masala.skills

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.SkillSchedule
import com.samosaai.masala.core.scheduled.RepeatKind
import com.samosaai.masala.core.scheduled.ScheduledTaskStore
import com.samosaai.masala.core.scheduled.TaskRunStatus
import com.samosaai.masala.core.scheduled.TaskStorage
import com.samosaai.masala.ui.home.formatNextRun
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillSchedulingTest {
    private class MemoryStorage : TaskStorage {
        var text: String? = null
        override fun read() = text
        override fun write(json: String) { text = json }
    }

    private class Declined : DeclinedSchedules {
        val ids = mutableSetOf<String>()
        override fun contains(skillId: String) = skillId in ids
        override fun add(skillId: String) { ids += skillId }
    }

    private val zone = ZoneId.of("UTC")
    private val store = ScheduledTaskStore(MemoryStorage())
    private val declined = Declined()
    private val scheduling = SkillScheduling(store, declined)
    private val now = LocalDateTime.parse("2026-10-28T10:00")
    private val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
    private val brief = MasalaSkill(id = "brief", title = "Brief", instructions = "i", schedule = SkillSchedule("daily 07:30"))

    @Test fun skillWithADefaultScheduleIsOffered() {
        val offer = assertNotNull(scheduling.offerFor(brief, now))
        assertEquals("every day at 07:30", offer.schedule.summary)
    }

    @Test fun skillsWithoutAScheduleOrAnUnreadableOneAreNotOffered() {
        assertNull(scheduling.offerFor(brief.copy(schedule = null), now))
        assertNull(scheduling.offerFor(brief.copy(schedule = SkillSchedule("whenever")), now))
    }

    @Test fun acceptingCreatesAnArmedDailyTaskForTheSkill() {
        val task = scheduling.accept(scheduling.offerFor(brief, now)!!, nowMillis, zone)
        assertEquals("brief", task.skillId)
        assertEquals(RepeatKind.DAILY, task.repeat.kind)
        assertEquals(LocalDateTime.parse("2026-10-29T07:30").atZone(zone).toInstant().toEpochMilli(), task.nextRunAt)
        assertEquals(task.nextRunAt, scheduling.nextRunAt("brief"))
        assertEquals(listOf(task), store.all())
    }

    @Test fun noSecondOfferOnceATaskExistsEvenIfSwitchedOff() {
        val task = scheduling.accept(scheduling.offerFor(brief, now)!!, nowMillis, zone)
        assertNull(scheduling.offerFor(brief, now))
        store.put(task.copy(enabled = false))
        assertNull(scheduling.offerFor(brief, now))
        assertNull(scheduling.nextRunAt("brief"))
    }

    @Test fun decliningIsRemembered() {
        scheduling.decline("brief")
        assertNull(scheduling.offerFor(brief, now))
        assertTrue(store.all().isEmpty())
    }

    @Test fun scheduledOutcomesMapRunStatuses() {
        assertEquals(TaskRunStatus.DONE to null, ScheduledOutcome.of("done", null))
        assertEquals(TaskRunStatus.STOPPED to null, ScheduledOutcome.of("cancelled", null))
        assertEquals(TaskRunStatus.FAILED to "offline", ScheduledOutcome.of("failed", "offline"))
        assertEquals(TaskRunStatus.FAILED to "The run did not finish.", ScheduledOutcome.of("running", null))
    }

    @Test fun nextRunIsShownWithTheDayUnlessItIsToday() {
        val today = LocalDate.parse("2026-10-28")
        val sevenThirty = LocalDateTime.parse("2026-10-28T07:30").atZone(zone).toInstant().toEpochMilli()
        val tomorrow = LocalDateTime.parse("2026-10-29T07:30").atZone(zone).toInstant().toEpochMilli()
        assertTrue(formatNextRun(sevenThirty, zone, today).none { it.isLetter() && it !in "AMP" }, "today has no weekday")
        assertTrue(formatNextRun(tomorrow, zone, today).startsWith("Thu"))
    }

    @Test fun setDailyCreatesOrMovesTheTaskAndTurnsItOn() {
        val created = scheduling.setDaily(brief, java.time.LocalTime.of(6, 45), now, nowMillis, zone)
        assertEquals("2026-10-29T06:45", created.startLocal)
        assertEquals(RepeatKind.DAILY, created.repeat.kind)
        assertNotNull(created.nextRunAt)
        store.put(created.copy(enabled = false))
        val moved = scheduling.setDaily(brief, java.time.LocalTime.of(11, 0), now, nowMillis, zone)
        assertEquals(created.id, moved.id)
        assertEquals("2026-10-28T11:00", moved.startLocal)
        assertTrue(moved.enabled)
        assertEquals(LocalDateTime.parse("2026-10-28T11:00").atZone(zone).toInstant().toEpochMilli(), moved.nextRunAt)
        assertEquals(1, store.all().size)
    }
}
