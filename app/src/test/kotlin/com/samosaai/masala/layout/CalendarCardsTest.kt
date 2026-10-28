package com.samosaai.masala.layout

import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.NextEvent
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarCardsTest {
    private val now = LocalDateTime.of(2026, 10, 28, 9, 0, 0)
    private fun event(title: String = "Standup", start: LocalDateTime) = NextEvent(title, start)

    @Test
    fun `minutes away reads naturally`() {
        assertEquals(
            "Next: Standup in 12 min",
            CalendarCards.nextEventLine(now, event(start = now.plusMinutes(12)))
        )
    }

    @Test
    fun `started event reads now`() {
        assertEquals("Next: Standup now", CalendarCards.nextEventLine(now, event(start = now)))
    }

    @Test
    fun `later today reads as time`() {
        assertEquals(
            "Next: Reviews at 15:00",
            CalendarCards.nextEventLine(now, event("Reviews", now.plusHours(6)))
        )
    }

    @Test
    fun `long-past events are ignored, just-started read as now`() {
        assertNull(CalendarCards.nextEventLine(now, event(start = now.minusHours(1))))
        assertEquals("Next: Standup now", CalendarCards.nextEventLine(now, event(start = now.minusMinutes(5))))
    }

    @Test
    fun `two-hour boundary switches wording`() {
        assertEquals(
            "Next: Standup in 119 min",
            CalendarCards.nextEventLine(now, event(start = now.plusMinutes(119)))
        )
        assertEquals(
            "Next: Standup at 11:00",
            CalendarCards.nextEventLine(now, event(start = now.plusMinutes(120)))
        )
    }

    @Test
    fun `custom buffer and exact-now leave-by`() {
        assertEquals(
            "Leave by 10:00",
            CalendarCards.leaveByLine(now, event(start = now.plusHours(4)), bufferMinutes = 180)
        )
        assertNull(CalendarCards.leaveByLine(now, event(start = now.plusMinutes(30)), bufferMinutes = 30))
    }

    @Test
    fun `tomorrow reads as day and time`() {
        assertEquals(
            "Next: Flight on Thu 08:00",
            CalendarCards.nextEventLine(now, event("Flight", now.plusDays(1).minusHours(1)))
        )
    }

    @Test
    fun `leave-by subtracts the buffer`() {
        assertEquals(
            "Leave by 09:30",
            CalendarCards.leaveByLine(now, event(start = now.plusHours(1)))
        )
    }

    @Test
    fun `no time left means no leave-by`() {
        assertNull(CalendarCards.leaveByLine(now, event(start = now.plusMinutes(20))))
    }

    @Test
    fun `no event means no lines`() {
        assertNull(CalendarCards.nextEventLine(now, null))
        assertNull(CalendarCards.summary(ContextSnapshot(now = now)))
    }

    @Test
    fun `summary joins both lines`() {
        val snapshot = ContextSnapshot(now = now, nextEvent = event(start = now.plusHours(2)))
        assertEquals(
            "Next: Standup at 11:00\nLeave by 10:30",
            CalendarCards.summary(snapshot)
        )
    }
}
