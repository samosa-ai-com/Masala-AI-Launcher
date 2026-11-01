package com.samosaai.masala.core.widgets

import com.samosaai.masala.core.contract.Binding
import com.samosaai.masala.core.contract.BindingSource
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.NextEvent
import com.samosaai.masala.core.contract.WeatherKind
import com.samosaai.masala.core.contract.WeatherNow
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetValuesTest {

    private val snapshot = ContextSnapshot(
        now = LocalDateTime.of(2026, 11, 1, 9, 30),
        nextEvent = NextEvent("Standup", LocalDateTime.of(2026, 11, 1, 10, 0), "Room 3"),
        batteryPercent = 82,
        sleepHours = 7.5,
        steps = 4200,
        weather = WeatherNow(WeatherKind.CLOUDY, 16.0),
        urgentCount = 2,
    )

    private fun binding(source: BindingSource, key: String? = null) = Binding(source, key)

    @Test
    fun storageReadsTheWidgetsOwnMap() {
        assertEquals("3", WidgetValues.textOf(binding(BindingSource.STORAGE, "glasses"), mapOf("glasses" to "3"), snapshot))
        assertNull(WidgetValues.textOf(binding(BindingSource.STORAGE, "missing"), emptyMap(), snapshot))
    }

    @Test
    fun snapshotSourcesFormat() {
        val empty = emptyMap<String, String>()
        assertEquals("Standup · 10:00 · Room 3", WidgetValues.textOf(binding(BindingSource.NEXT_EVENT), empty, snapshot))
        assertEquals("82%", WidgetValues.textOf(binding(BindingSource.BATTERY), empty, snapshot))
        assertEquals("4200", WidgetValues.textOf(binding(BindingSource.STEPS), empty, snapshot))
        assertEquals("7.5 h", WidgetValues.textOf(binding(BindingSource.SLEEP), empty, snapshot))
        assertEquals("16°C cloudy", WidgetValues.textOf(binding(BindingSource.WEATHER), empty, snapshot))
        assertEquals("2", WidgetValues.textOf(binding(BindingSource.URGENT_COUNT), empty, snapshot))
        assertNull(WidgetValues.textOf(binding(BindingSource.URGENT_COUNT), empty, snapshot.copy(urgentCount = 0)))
    }

    @Test
    fun futureStoresResolveToNull() {
        val empty = emptyMap<String, String>()
        assertNull(WidgetValues.textOf(binding(BindingSource.HTTP), empty, snapshot))
        assertNull(WidgetValues.textOf(binding(BindingSource.STREAM_STATUS), empty, snapshot))
        assertNull(WidgetValues.textOf(binding(BindingSource.SKILL_RESULT), empty, snapshot))
        assertNull(WidgetValues.textOf(binding(BindingSource.MEDIA), empty, snapshot))
    }

    @Test
    fun numbersPreferNumericSources() {
        val storage = mapOf("level" to "0.75", "junk" to "abc")
        assertEquals(0.75, WidgetValues.numberOf(binding(BindingSource.STORAGE, "level"), storage, snapshot))
        assertNull(WidgetValues.numberOf(binding(BindingSource.STORAGE, "junk"), storage, snapshot))
        assertEquals(82.0, WidgetValues.numberOf(binding(BindingSource.BATTERY), emptyMap(), snapshot))
        assertNull(WidgetValues.numberOf(binding(BindingSource.NEXT_EVENT), emptyMap(), snapshot))
    }

    @Test
    fun rowsSplitStorageAndCapAtMax() {
        val storage = mapOf("todos" to "a\n\nb\nc")
        assertEquals(listOf("a", "b"), WidgetValues.rowsOf(binding(BindingSource.STORAGE, "todos"), storage, snapshot, 2))
        assertEquals(listOf("Standup · 10:00 · Room 3"), WidgetValues.rowsOf(binding(BindingSource.NEXT_EVENT), emptyMap(), snapshot, 3))
        assertEquals(emptyList(), WidgetValues.rowsOf(binding(BindingSource.BATTERY), emptyMap(), snapshot, 3))
    }
}
