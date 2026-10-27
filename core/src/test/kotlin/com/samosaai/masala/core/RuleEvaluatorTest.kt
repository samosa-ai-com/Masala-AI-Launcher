package com.samosaai.masala.core

import com.samosaai.masala.core.Fixtures.at
import com.samosaai.masala.core.contract.Condition
import com.samosaai.masala.core.contract.Effect
import com.samosaai.masala.core.contract.NextEvent
import com.samosaai.masala.core.contract.Rule
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.WeatherKind
import com.samosaai.masala.core.contract.WeatherNow
import com.samosaai.masala.core.contract.Weekday
import com.samosaai.masala.core.rules.RuleEvaluator
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleEvaluatorTest {
    private fun window(start: String, end: String, vararg days: Weekday) =
        Condition.TimeWindow(LocalTime.parse(start), LocalTime.parse(end), days.toList())

    @Test
    fun `daytime window`() {
        val w = window("09:00", "17:00")
        assertFalse(RuleEvaluator.evaluate(w, at("08:59")))
        assertTrue(RuleEvaluator.evaluate(w, at("09:00")))
        assertTrue(RuleEvaluator.evaluate(w, at("16:59")))
        assertFalse(RuleEvaluator.evaluate(w, at("17:00")))
    }

    @Test
    fun `window past midnight belongs to the day it started`() {
        val tuesdayNights = window("22:30", "07:00", Weekday.TUE)
        assertTrue(RuleEvaluator.evaluate(tuesdayNights, at("23:00"))) // Tuesday evening
        assertTrue(RuleEvaluator.evaluate(tuesdayNights, at("03:00", day = 28))) // Wednesday morning
        assertFalse(RuleEvaluator.evaluate(tuesdayNights, at("03:00"))) // Tuesday morning: Monday's night
        assertFalse(RuleEvaluator.evaluate(tuesdayNights, at("12:00")))
    }

    @Test
    fun `window days`() {
        val weekdays = window("09:00", "17:00", Weekday.MON, Weekday.WED)
        assertTrue(RuleEvaluator.evaluate(weekdays, at("10:00", day = 26))) // Monday
        assertFalse(RuleEvaluator.evaluate(weekdays, at("10:00"))) // Tuesday
    }

    @Test
    fun `next event`() {
        val ctx = at("09:00").copy(nextEvent = NextEvent("Standup", at("09:12").now))
        assertTrue(RuleEvaluator.evaluate(Condition.MinutesToNextEvent(maxMinutes = 15), ctx))
        assertFalse(RuleEvaluator.evaluate(Condition.MinutesToNextEvent(maxMinutes = 10), ctx))
        assertFalse(RuleEvaluator.evaluate(Condition.MinutesToNextEvent(maxMinutes = 60), at("09:00")))
    }

    @Test
    fun `missing signals are false, and not() of them is true`() {
        val ctx = at("09:00")
        assertFalse(RuleEvaluator.evaluate(Condition.Charging(), ctx))
        assertFalse(RuleEvaluator.evaluate(Condition.Charging(charging = false), ctx))
        assertFalse(RuleEvaluator.evaluate(Condition.Steps(min = 0), ctx))
        assertFalse(RuleEvaluator.evaluate(Condition.Sleep(maxHours = 24.0), ctx))
        assertFalse(RuleEvaluator.evaluate(Condition.Weather(), ctx))
        assertTrue(RuleEvaluator.evaluate(Condition.Not(Condition.Charging()), ctx))
    }

    @Test
    fun `signals that are present`() {
        val ctx = at("09:00").copy(
            charging = true,
            bluetoothDevices = listOf("Car Kit"),
            activeModes = listOf("focus"),
            urgentCount = 2,
            sleepHours = 5.5,
            steps = 4000,
            weather = WeatherNow(WeatherKind.RAIN, 18.0),
        )
        assertTrue(RuleEvaluator.evaluate(Condition.Charging(), ctx))
        assertTrue(RuleEvaluator.evaluate(Condition.BluetoothConnected(), ctx))
        assertTrue(RuleEvaluator.evaluate(Condition.BluetoothConnected("car kit"), ctx))
        assertFalse(RuleEvaluator.evaluate(Condition.BluetoothConnected("Headphones"), ctx))
        assertTrue(RuleEvaluator.evaluate(Condition.ModeActive("focus"), ctx))
        assertTrue(RuleEvaluator.evaluate(Condition.UrgentCount(2), ctx))
        assertFalse(RuleEvaluator.evaluate(Condition.UrgentCount(3), ctx))
        assertTrue(RuleEvaluator.evaluate(Condition.Sleep(maxHours = 6.0), ctx))
        assertTrue(RuleEvaluator.evaluate(Condition.Steps(max = 5000), ctx))
        assertTrue(RuleEvaluator.evaluate(Condition.Weather(kinds = listOf(WeatherKind.RAIN), maxTempC = 20.0), ctx))
        assertFalse(RuleEvaluator.evaluate(Condition.Weather(kinds = listOf(WeatherKind.CLEAR)), ctx))
        val both = Condition.AllOf(listOf(Condition.Charging(), Condition.ModeActive("focus")))
        assertTrue(RuleEvaluator.evaluate(both, ctx))
        assertTrue(RuleEvaluator.evaluate(Condition.AnyOf(listOf(Condition.ModeActive("x"), Condition.Charging())), ctx))
        assertTrue(RuleEvaluator.evaluate(Condition.AllOf(emptyList()), ctx))
        assertFalse(RuleEvaluator.evaluate(Condition.AnyOf(emptyList()), ctx))
    }

    @Test
    fun `effects combine - last visibility wins, largest size clamped, highest priority`() {
        val always = Condition.AllOf(emptyList())
        val never = Condition.AnyOf(emptyList())
        val rules = listOf(
            Rule(always, Effect.Hide),
            Rule(always, Effect.Show),
            Rule(never, Effect.Hide),
            Rule(always, Effect.Size(3)),
            Rule(always, Effect.Size(9)),
            Rule(always, Effect.Priority(5)),
            Rule(always, Effect.Priority(-1)),
        )
        val r = RuleEvaluator.resolve(rules, at("09:00"), SizeRange(min = 1, default = 2, max = 4))
        assertEquals(RuleEvaluator.Resolution(visible = true, rows = 4, priority = 5), r)

        val none = RuleEvaluator.resolve(emptyList(), at("09:00"), SizeRange(min = 1, default = 2, max = 4))
        assertEquals(RuleEvaluator.Resolution(visible = true, rows = 2, priority = 0), none)
    }
}
