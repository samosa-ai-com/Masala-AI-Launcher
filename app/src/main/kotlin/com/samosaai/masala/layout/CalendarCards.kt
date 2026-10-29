package com.samosaai.masala.layout

import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.NextEvent
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Calendar lines for the layout prompt (plan Phase 3, lane C): what is next and when to leave.
 * Pure functions of the snapshot, so the wording is unit-tested.
 */
object CalendarCards {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)

    /** Events older than this are over; never call them "next". */
    const val PAST_CUTOFF_MINUTES = -15L

    /** "Next: Standup in 12 min". Null with no event, or one that started a while ago. */
    fun nextEventLine(now: LocalDateTime, event: NextEvent?): String? {
        if (event == null) return null
        val minutes = ChronoUnit.MINUTES.between(now, event.start)
        if (minutes < PAST_CUTOFF_MINUTES) return null
        val whenText = when {
            minutes < 1 -> "now"
            minutes < 120 -> "in $minutes min"
            event.start.toLocalDate() == now.toLocalDate() -> "at ${event.start.format(TIME)}"
            else -> "on ${event.start.format(DAY_TIME)}"
        }
        return "Next: ${event.title} $whenText"
    }

    /** "Leave by 18:10": event start minus [bufferMinutes]. Null with no event or no time left. */
    fun leaveByLine(now: LocalDateTime, event: NextEvent?, bufferMinutes: Long = 30): String? {
        if (event == null) return null
        val leave = event.start.minusMinutes(bufferMinutes)
        if (!leave.isAfter(now)) return null
        val at = if (leave.toLocalDate() == now.toLocalDate()) leave.format(TIME) else leave.format(DAY_TIME)
        return "Leave by $at"
    }

    /** Both lines for the prompt context. Null when the calendar says nothing usable. */
    fun summary(snapshot: ContextSnapshot): String? =
        listOfNotNull(
            nextEventLine(snapshot.now, snapshot.nextEvent),
            leaveByLine(snapshot.now, snapshot.nextEvent),
        ).joinToString("\n").takeIf { it.isNotBlank() }
}
