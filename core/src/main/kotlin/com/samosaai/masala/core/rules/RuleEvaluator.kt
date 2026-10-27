package com.samosaai.masala.core.rules

import com.samosaai.masala.core.contract.Condition
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.Effect
import com.samosaai.masala.core.contract.Rule
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.Weekday
import java.time.Duration
import java.time.LocalDateTime

/** Runs rules on the phone, on every render and context change (arch §3.3). Pure and offline. */
object RuleEvaluator {

    fun evaluate(condition: Condition, ctx: ContextSnapshot): Boolean = when (condition) {
        is Condition.TimeWindow -> inWindow(condition, ctx.now)
        is Condition.MinutesToNextEvent -> ctx.nextEvent?.let {
            val minutes = Duration.between(ctx.now, it.start).toMinutes()
            minutes in condition.minMinutes..condition.maxMinutes
        } ?: false
        is Condition.Charging -> ctx.charging?.let { it == condition.charging } ?: false
        is Condition.BluetoothConnected -> condition.deviceName
            ?.let { name -> ctx.bluetoothDevices.any { it.equals(name, ignoreCase = true) } }
            ?: ctx.bluetoothDevices.isNotEmpty()
        is Condition.ModeActive -> condition.modeId in ctx.activeModes
        is Condition.UrgentCount -> ctx.urgentCount >= condition.atLeast
        is Condition.Sleep -> ctx.sleepHours?.let { inRange(it, condition.minHours, condition.maxHours) } ?: false
        is Condition.Steps -> ctx.steps?.let {
            inRange(it.toDouble(), condition.min?.toDouble(), condition.max?.toDouble())
        } ?: false
        is Condition.Weather -> ctx.weather?.let {
            (condition.kinds.isEmpty() || it.kind in condition.kinds) &&
                inRange(it.tempC, condition.minTempC, condition.maxTempC)
        } ?: false
        is Condition.AllOf -> condition.conditions.all { evaluate(it, ctx) }
        is Condition.AnyOf -> condition.conditions.any { evaluate(it, ctx) }
        is Condition.Not -> !evaluate(condition.condition, ctx)
    }

    /**
     * Combines the effects of every rule whose condition holds: the last `show`/`hide` wins (shown
     * by default), the largest `size` clamped to [sizeRange], and the highest `priority` (0 by default).
     */
    fun resolve(rules: List<Rule>, ctx: ContextSnapshot, sizeRange: SizeRange? = null): Resolution {
        var visible = true
        var rows: Int? = null
        var priority: Int? = null
        for (rule in rules) {
            if (!evaluate(rule.condition, ctx)) continue
            when (val effect = rule.effect) {
                Effect.Show -> visible = true
                Effect.Hide -> visible = false
                is Effect.Size -> rows = maxOf(rows ?: effect.rows, effect.rows)
                is Effect.Priority -> priority = maxOf(priority ?: effect.value, effect.value)
            }
        }
        val size = sizeRange?.let { range -> (rows ?: range.default).coerceIn(range.min, range.max) }
        return Resolution(visible, size, priority ?: 0)
    }

    data class Resolution(val visible: Boolean, val rows: Int?, val priority: Int)

    private fun inWindow(w: Condition.TimeWindow, now: LocalDateTime): Boolean {
        val time = now.toLocalTime()
        val today = now.dayOfWeek.toWeekday()
        val yesterday = now.dayOfWeek.minus(1).toWeekday()
        fun startsOn(day: Weekday) = w.days.isEmpty() || day in w.days
        return when {
            w.start == w.end -> startsOn(today) // the whole day
            w.start < w.end -> startsOn(today) && time >= w.start && time < w.end
            // Past midnight: the evening part started today, the morning part started yesterday.
            else -> (time >= w.start && startsOn(today)) || (time < w.end && startsOn(yesterday))
        }
    }

    private fun inRange(value: Double, min: Double?, max: Double?) =
        (min == null || value >= min) && (max == null || value <= max)

    private fun java.time.DayOfWeek.toWeekday() = Weekday.entries[ordinal]
}
