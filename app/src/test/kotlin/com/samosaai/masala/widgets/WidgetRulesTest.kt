package com.samosaai.masala.widgets

import com.samosaai.masala.core.contract.Binding
import com.samosaai.masala.core.contract.BindingSource
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetPrefs
import com.samosaai.masala.core.contract.WidgetSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Daily/weekly/monthly resets clear the value once per period, not on every composition. */
class WidgetRulesTest {
    private fun store() = WidgetStateStore(CoroutineScope(Dispatchers.Unconfined), null)

    private val water = WidgetSpec(
        id = "water",
        components = listOf(WidgetComponent.Counter(key = "glasses", label = "Glasses", goal = 8, reset = "daily")),
    )

    @Test fun `a daily counter starts over once a day`() = runBlocking {
        val s = store()
        s.set("water", "glasses", "5")
        WidgetRules.apply(s, "water", water, WidgetPrefs(), LocalDate.of(2026, 11, 3))
        assertEquals("0", s.values("water")["glasses"])

        s.set("water", "glasses", "3")
        // Same day: the value the user just tapped stays.
        WidgetRules.apply(s, "water", water, WidgetPrefs(), LocalDate.of(2026, 11, 3))
        assertEquals("3", s.values("water")["glasses"])

        WidgetRules.apply(s, "water", water, WidgetPrefs(), LocalDate.of(2026, 11, 4))
        assertEquals("0", s.values("water")["glasses"])
    }

    @Test fun `a checklist clears its ticks and a countdown with a date stores nothing`() = runBlocking {
        val s = store()
        val habits = WidgetSpec(
            id = "habits",
            components = listOf(WidgetComponent.Checklist(key = "done", items = listOf("a", "b"), reset = "daily")),
        )
        s.set("habits", "done", "[true,true]")
        WidgetRules.apply(s, "habits", habits, WidgetPrefs(), LocalDate.of(2026, 11, 3))
        assertEquals(false, s.bools("habits", "done", 2).any { it })

        val days = WidgetSpec(
            id = "days",
            components = listOf(WidgetComponent.Counter(key = "d", label = "Days", date = "2026-12-01", reset = "daily")),
        )
        s.set("days", "d", "4")
        WidgetRules.apply(s, "days", days, WidgetPrefs(), LocalDate.of(2026, 11, 3))
        assertEquals("4", s.values("days")["d"])
    }

    @Test fun `never disables a template's daily reset`() = runBlocking {
        val s = store()
        s.set("water", "glasses", "4")
        val prefs = WidgetPrefs(components = mapOf("counter:glasses" to com.samosaai.masala.core.contract.ComponentPrefs(reset = "never")))
        WidgetRules.apply(s, "water", water, prefs, LocalDate.of(2026, 11, 3))
        assertEquals("4", s.values("water")["glasses"])
        WidgetRules.apply(s, "water", water, prefs, LocalDate.of(2026, 11, 4))
        assertEquals("4", s.values("water")["glasses"])
    }

    @Test fun `a reset at zero still records the period, so a later tap survives a restart`() = runBlocking {
        val s = store()
        WidgetRules.apply(s, "water", water, WidgetPrefs(), LocalDate.of(2026, 11, 3))
        assertEquals("0", s.values("water")["glasses"])
        s.set("water", "glasses", "3")
        WidgetRules.apply(s, "water", water, WidgetPrefs(), LocalDate.of(2026, 11, 3))
        assertEquals("3", s.values("water")["glasses"])
    }

    @Test fun `a monthly budget resets on the first of the month`() = runBlocking {
        val s = store()
        val budget = WidgetSpec(
            id = "budget",
            components = listOf(
                WidgetComponent.Progress(
                    label = "Spent",
                    binding = Binding(BindingSource.STORAGE, "spent"),
                    max = 1000.0,
                    reset = "monthly",
                ),
            ),
        )
        s.set("budget", "spent", "120")
        WidgetRules.apply(s, "budget", budget, WidgetPrefs(), LocalDate.of(2026, 11, 3))
        assertEquals("0", s.values("budget")["spent"])
        s.set("budget", "spent", "50")
        WidgetRules.apply(s, "budget", budget, WidgetPrefs(), LocalDate.of(2026, 12, 1))
        assertEquals("0", s.values("budget")["spent"])
    }
}
