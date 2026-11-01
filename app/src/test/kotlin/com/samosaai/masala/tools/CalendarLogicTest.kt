package com.samosaai.masala.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RRULE building, reminder and attendee parsing: the provider calls stay thin and untested. */
class CalendarLogicTest {
    @Test fun `a plain create has no rule`() {
        assertEquals(null to null, CalendarTool.rrule(null, null, null))
    }

    @Test fun `frequencies map onto FREQ`() {
        assertEquals("FREQ=DAILY" to null, CalendarTool.rrule("daily", null, null))
        assertEquals("FREQ=WEEKLY" to null, CalendarTool.rrule(" Weekly ", null, null))
        assertEquals("FREQ=MONTHLY" to null, CalendarTool.rrule("monthly", null, null))
        assertEquals("FREQ=YEARLY" to null, CalendarTool.rrule("yearly", null, null))
    }

    @Test fun `an unknown repeat is refused`() {
        val (rule, error) = CalendarTool.rrule("hourly", null, null)
        assertNull(rule)
        assertTrue(error!!.contains("daily"))
    }

    @Test fun `count and end need a frequency`() {
        val (_, error) = CalendarTool.rrule(null, 5, null)
        assertTrue(error!!.contains("recurrence"))
    }

    @Test fun `count must be positive`() {
        assertTrue(CalendarTool.rrule("daily", 0, null).second!!.contains("at least 1"))
        assertEquals("FREQ=DAILY;COUNT=10" to null, CalendarTool.rrule("daily", 10, null))
    }

    @Test fun `until becomes a UTC timestamp`() {
        val (rule, error) = CalendarTool.rrule("weekly", null, "2026-12-31")
        assertNull(error)
        assertTrue(rule!!.startsWith("FREQ=WEEKLY;UNTIL=20") && rule.endsWith("Z"), rule)
        assertTrue(rule.endsWith("Z"), rule)
        val (_, bad) = CalendarTool.rrule("weekly", null, "not a date")
        assertTrue(bad!!.contains("2026-12-31"))
    }

    @Test fun `reminders are bounded`() {
        assertEquals(null to null, CalendarTool.validatedReminder(null))
        assertEquals(30 to null, CalendarTool.validatedReminder(30))
        assertEquals(0 to null, CalendarTool.validatedReminder(0))
        assertTrue(CalendarTool.validatedReminder(-5).second!!.contains("-5"))
        assertTrue(CalendarTool.validatedReminder(99999).second!!.contains("99999"))
    }

    @Test fun `attendees split on commas and semicolons and drop junk`() {
        assertEquals(
            listOf("a@x.com", "b@y.org"),
            CalendarTool.splitAttendees(" a@x.com, not-an-email; b@y.org "),
        )
        assertEquals(emptyList(), CalendarTool.splitAttendees(null))
        assertEquals(emptyList(), CalendarTool.splitAttendees("nobody"))
    }

    @Test fun `durations use days for whole days and seconds otherwise`() {
        assertEquals("P1D", CalendarTool.durationOf(0L, 86_400_000L))
        assertEquals("PT3600S", CalendarTool.durationOf(0L, 3_600_000L))
        assertEquals("P0D", CalendarTool.durationOf(1000L, 500L))
    }

    @Test fun `provider durations parse back to millis`() {
        assertEquals(3_600_000L, CalendarTool.parseDuration("PT1H"))
        assertEquals(86_400_000L, CalendarTool.parseDuration("P1D"))
        assertEquals(604_800_000L, CalendarTool.parseDuration("P1W"))
        assertEquals(1_800_000L, CalendarTool.parseDuration("PT30M"))
        assertEquals(null, CalendarTool.parseDuration("tomorrow"))
    }

    private fun stored(
        startAt: Long = 1_793_610_000_000L,
        endAt: Long? = 1_793_613_600_000L,
        durationMillis: Long? = null,
        allDay: Boolean = false,
        rrule: String? = null,
    ) = CalendarTool.StoredEvent(startAt, endAt, durationMillis, allDay, rrule)

    @Test fun `a bare date moves the day and keeps the time`() {
        // 2026-11-02T09:00 UTC.
        val span = CalendarTool.spanForEdit(
            stored(), "2026-11-05", null, null, null, false, java.time.ZoneId.of("UTC"),
        )
        assertEquals(1_793_869_200_000L, span.dtstart)
        assertEquals(null, span.duration)
        assertEquals(false, span.allDay)
    }

    @Test fun `making it all-day lands on UTC midnights`() {
        val span = CalendarTool.spanForEdit(
            stored(), null, null, true, null, false, java.time.ZoneId.of("UTC"),
        )
        assertTrue(span.allDay && span.timezone == "UTC")
        assertEquals(1_793_577_600_000L, span.dtstart)
        assertEquals(1_793_664_000_000L, span.dtend)
        assertEquals(null, span.duration)
    }

    @Test fun `a repeat swaps the end instant for a duration`() {
        val span = CalendarTool.spanForEdit(
            stored(), null, null, null, "FREQ=DAILY", false, java.time.ZoneId.of("UTC"),
        )
        assertEquals("PT3600S", span.duration)
        assertEquals(null, span.dtend)
    }

    @Test fun `clearing the repeat brings back an end instant`() {
        val span = CalendarTool.spanForEdit(
            stored(endAt = null, durationMillis = 3_600_000L, rrule = "FREQ=DAILY"),
            null, null, null, null, true, java.time.ZoneId.of("UTC"),
        )
        assertEquals(null, span.duration)
        assertEquals(1_793_610_000_000L + 3_600_000L, span.dtend)
    }
}
