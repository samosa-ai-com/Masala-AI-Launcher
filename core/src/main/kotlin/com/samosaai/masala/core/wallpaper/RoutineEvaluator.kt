package com.samosaai.masala.core.wallpaper

import com.samosaai.masala.core.contract.ContextSnapshot
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Decides which wallpaper a set of routines wants right now. Pure: the same snapshot always gives the same
 * answer,
 * so the engine, the settings preview and the tests all agree.
 */
object RoutineEvaluator {

    /** What to show: [ruleIndex] is null when the routine's default won. */
    data class Match(val routineId: String, val ruleIndex: Int?, val source: String)

    /** Every condition holds. A missing signal (no weather, no battery) fails only the test that needs it. */
    fun matches(conditions: RoutineConditions, snapshot: ContextSnapshot): Boolean {
        if (!withinTime(conditions, snapshot.now)) return false
        if (conditions.days.isNotEmpty() && snapshot.now.dayOfWeek.value !in conditions.days) return false
        if (conditions.weather.isNotEmpty() && snapshot.weather?.kind !in conditions.weather) return false
        if (conditions.charging != null && snapshot.charging != conditions.charging) return false
        if (conditions.batteryBelow != null) {
            val battery = snapshot.batteryPercent ?: return false
            if (battery >= conditions.batteryBelow) return false
        }
        if (conditions.minTempC != null) {
            val temp = snapshot.weather?.tempC ?: return false
            if (temp < conditions.minTempC) return false
        }
        if (conditions.maxTempC != null) {
            val temp = snapshot.weather?.tempC ?: return false
            if (temp > conditions.maxTempC) return false
        }
        return true
    }

    /**
     * The first enabled routine with a matching rule, or its default; null when nothing wants to
     * change. Order is the user's: earlier routines win.
     */
    fun choose(routines: List<WallpaperRoutine>, snapshot: ContextSnapshot): Match? {
        val enabled = routines.filter { it.enabled }
        // Every routine's matching rules are considered before any routine's default, so a
        // routine's fallback can never shadow a later routine's real match. A default is only a
        // fallback for the whole set.
        for (routine in enabled) {
            val index = routine.rules.indexOfFirst { matches(it.conditions, snapshot) }
            if (index >= 0) return Match(routine.id, index, routine.rules[index].source)
        }
        return enabled.firstNotNullOfOrNull { routine ->
            routine.defaultSource?.let { Match(routine.id, null, it) }
        }
    }

    /**
     * The next moment any of [routines]' time windows could change what should show, or null when
     * no rule has a time window. The engine re-evaluates then instead of polling.
     */
    fun nextBoundary(routines: List<WallpaperRoutine>, now: LocalDateTime): LocalDateTime? {
        val candidates = mutableListOf<LocalDateTime>()
        routines.filter { it.enabled }.forEach { routine ->
            routine.rules.forEach { rule ->
                val from = minutesOf(rule.conditions.from) ?: return@forEach
                val to = minutesOf(rule.conditions.to) ?: return@forEach
                candidates += nextOccurrence(now, from)
                candidates += nextOccurrence(now, to)
            }
        }
        return candidates.filter { it.isAfter(now) }.minOrNull()
    }

    /** True when [now] falls inside the rule's window; a window that ends before it starts crosses midnight. */
    internal fun withinTime(conditions: RoutineConditions, now: LocalDateTime): Boolean {
        val from = minutesOf(conditions.from)
        val to = minutesOf(conditions.to)
        if (from == null || to == null) return true
        if (from == to) return false
        val minute = now.hour * 60 + now.minute
        return if (from < to) minute in from until to else minute >= from || minute < to
    }

    /** "06:00" or "6:00" as minutes after midnight; null when it isn't a time. */
    fun minutesOf(text: String?): Int? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = value.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun nextOccurrence(now: LocalDateTime, minutes: Int): LocalDateTime {
        val today = at(now.toLocalDate(), minutes)
        return if (today.isAfter(now)) today else at(now.toLocalDate().plusDays(1), minutes)
    }

    private fun at(day: LocalDate, minutes: Int): LocalDateTime =
        day.atStartOfDay().plusMinutes(minutes.toLong())
}
