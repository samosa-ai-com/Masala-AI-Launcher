package com.samosaai.masala.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Per-widget settings apply to a copy of the spec, by key, including inside rows. */
class WidgetPrefsTest {
    private fun spec() = WidgetSpec(
        id = "w",
        title = "Water",
        components = listOf(
            WidgetComponent.Counter(key = "glasses", label = "Glasses", goal = 8),
            WidgetComponent.Row(
                listOf(
                    WidgetComponent.Timer(key = "work", label = "Focus", seconds = 1500, sound = true, autoStartKey = "break"),
                    WidgetComponent.Timer(key = "break", label = "Break", seconds = 300, sound = true, autoStartKey = "work"),
                ),
            ),
        ),
    )

    @Test fun `a goal and a target date belong to the placement`() {
        val resolved = spec().resolved(
            WidgetPrefs(
                title = "My water",
                components = mapOf(
                    "counter:glasses" to ComponentPrefs(goal = 10, reset = "daily"),
                    "timer:work" to ComponentPrefs(seconds = 1200, sound = false),
                ),
            ),
        )
        assertEquals("My water", resolved.title)
        val counter = resolved.components[0] as WidgetComponent.Counter
        assertEquals(10, counter.goal)
        assertEquals("daily", counter.reset)
        val row = resolved.components[1] as WidgetComponent.Row
        val work = row.children[0] as WidgetComponent.Timer
        assertEquals(1200, work.seconds)
        assertEquals(false, work.sound)
        // The chain a template declares is kept.
        assertEquals("break", work.autoStartKey)
        // An untouched sibling keeps the spec's value.
        assertEquals(300, (row.children[1] as WidgetComponent.Timer).seconds)
    }

    @Test fun `without settings the spec is unchanged`() {
        val resolved = spec().resolved(WidgetPrefs())
        assertEquals(spec(), resolved)
        assertNull((resolved.components[0] as WidgetComponent.Counter).date)
    }

    @Test fun `paths are stable per component key`() {
        val paths = spec().componentsWithPaths().map { it.first }
        assertEquals(listOf("counter:glasses", "index:1", "timer:work", "timer:break"), paths)
    }
}
