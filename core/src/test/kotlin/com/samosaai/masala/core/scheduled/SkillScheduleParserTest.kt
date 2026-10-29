package com.samosaai.masala.core.scheduled

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkillScheduleParserTest {
    // Wednesday 2026-10-28, 10:00.
    private val now = LocalDateTime.parse("2026-10-28T10:00")

    @Test fun dailyStartsTodayWhenTheTimeIsAhead() {
        val s = SkillScheduleParser.parse("daily 18:30", now)!!
        assertEquals("2026-10-28T18:30", s.startLocal)
        assertEquals(RepeatKind.DAILY, s.repeat.kind)
        assertEquals("every day at 18:30", s.summary)
    }

    @Test fun dailyStartsTomorrowWhenTheTimeHasPassed() {
        assertEquals("2026-10-29T07:30", SkillScheduleParser.parse("daily 07:30", now)!!.startLocal)
        assertEquals("2026-10-29T10:00", SkillScheduleParser.parse("daily 10:00", now)!!.startLocal)
    }

    @Test fun weeklyPicksTheNextListedWeekday() {
        val s = SkillScheduleParser.parse("weekly mon,fri 09:00", now)!!
        assertEquals("2026-10-30T09:00", s.startLocal)
        assertEquals(listOf(1, 5), s.repeat.weekdays)
        assertEquals("every Monday, Friday at 09:00", s.summary)
        // Today is a listed day but the time has passed: a week on.
        assertEquals("2026-11-04T09:00", SkillScheduleParser.parse("weekly wed 09:00", now)!!.startLocal)
        assertEquals("2026-10-28T11:00", SkillScheduleParser.parse("weekly wed 11:00", now)!!.startLocal)
    }

    @Test fun monthlyUsesTheDayOrTheMonthsLastDay() {
        assertEquals("2026-10-31T08:00", SkillScheduleParser.parse("monthly 31 08:00", now)!!.startLocal)
        assertEquals("2026-11-05T08:00", SkillScheduleParser.parse("monthly 5 08:00", now)!!.startLocal)
        assertEquals("2026-11-30T08:00", SkillScheduleParser.parse("monthly 31 08:00", LocalDateTime.parse("2026-10-31T09:00"))!!.startLocal)
    }

    @Test fun invalidSchedulesAreNull() {
        for (text in listOf("", "daily", "daily 25:00", "daily 7:61", "weekly xyz 09:00", "weekly 09:00", "monthly 0 08:00", "monthly 32 08:00", "yearly 09:00", "daily 09:00 extra", "every day"))
            assertNull(SkillScheduleParser.parse(text, now), text)
    }

    @Test fun theBundledExampleParses() {
        assertEquals("2026-10-29T07:30", SkillScheduleParser.parse("daily 07:30", now)!!.startLocal)
    }
}
