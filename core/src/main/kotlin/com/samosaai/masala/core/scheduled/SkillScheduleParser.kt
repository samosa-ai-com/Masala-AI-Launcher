package com.samosaai.masala.core.scheduled

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** A skill's `schedule.default` turned into a start time and a repeat rule for a [ScheduledTask]. */
data class SkillSchedule(val startLocal: String, val repeat: Repeat, val time: LocalTime, val summary: String)

/**
 * Reads `schedule.default` (`daily HH:mm`, `weekly <mon…sun[,…]> HH:mm`, `monthly <1–31> HH:mm`, arch
 * §3.9) and picks the first time that is still ahead of `now`. Null for anything else.
 */
object SkillScheduleParser {
    private val time = Regex("""(\d{1,2}):(\d{2})""")
    private val days = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    fun parse(text: String, now: LocalDateTime): SkillSchedule? {
        val parts = text.trim().lowercase().split(Regex("\\s+"))
        val clock = parts.lastOrNull()?.let { time.matchEntire(it) } ?: return null
        val at = runCatching { LocalTime.of(clock.groupValues[1].toInt(), clock.groupValues[2].toInt()) }.getOrNull() ?: return null
        val label = "%02d:%02d".format(at.hour, at.minute)
        return when (parts.first()) {
            "daily" -> if (parts.size != 2) null else {
                val today = now.toLocalDate().atTime(at)
                val start = if (today.isAfter(now)) today else today.plusDays(1)
                SkillSchedule(start.toString(), Repeat(RepeatKind.DAILY), at, "every day at $label")
            }
            "weekly" -> if (parts.size != 3) null else {
                val wanted = parts[1].split(',').map { d -> days.indexOf(d.take(3)).takeIf { it >= 0 } ?: return null }.map { it + 1 }.distinct().sorted()
                // A week and a day always reach a listed weekday whose time is still ahead.
                val start = generateSequence(now.toLocalDate()) { it.plusDays(1) }.take(8).map { it.atTime(at) }
                    .first { it.dayOfWeek.value in wanted && it.isAfter(now) }
                val names = wanted.joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.FULL, Locale.ENGLISH) }
                SkillSchedule(start.toString(), Repeat(RepeatKind.WEEKLY, weekdays = wanted), at, "every $names at $label")
            }
            "monthly" -> if (parts.size != 3) null else {
                val day = parts[1].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
                var month = YearMonth.from(now)
                var start = month.atDay(minOf(day, month.lengthOfMonth())).atTime(at)
                if (!start.isAfter(now)) {
                    month = month.plusMonths(1)
                    start = month.atDay(minOf(day, month.lengthOfMonth())).atTime(at)
                }
                SkillSchedule(start.toString(), Repeat(RepeatKind.MONTHLY), at, "every month on day $day at $label")
            }
            else -> null
        }
    }
}
