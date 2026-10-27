package com.samosaai.masala.core

import com.samosaai.masala.core.contract.ContextOverride
import com.samosaai.masala.core.contract.ContextOverride.Override
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.NextEvent
import com.samosaai.masala.core.contract.WeatherKind
import com.samosaai.masala.core.contract.WeatherNow
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextOverrideTest {
    private val real = ContextSnapshot(
        now = LocalDateTime.parse("2026-10-27T10:00"),
        charging = false,
        batteryPercent = 80,
        nextEvent = NextEvent("Standup", LocalDateTime.parse("2026-10-27T11:00")),
        activeModes = listOf("focus"),
        urgentCount = 2,
    )

    @Test
    fun `an empty override changes nothing`() {
        assertTrue(ContextOverride().isEmpty)
        assertEquals(real, ContextOverride().apply(real))
    }

    @Test
    fun `set fields replace real ones and the rest stay`() {
        val o = ContextOverride(
            now = LocalDateTime.parse("2026-10-27T23:30"),
            charging = Override(true),
            urgentCount = 0,
            weather = Override(WeatherNow(WeatherKind.RAIN, 7.0)),
        )
        val s = o.apply(real)
        assertEquals(LocalDateTime.parse("2026-10-27T23:30"), s.now)
        assertEquals(true, s.charging)
        assertEquals(0, s.urgentCount)
        assertEquals(WeatherKind.RAIN, s.weather?.kind)
        assertEquals(80, s.batteryPercent)
        assertEquals(listOf("focus"), s.activeModes)
    }

    @Test
    fun `a wrapped null hides a signal that is really there`() {
        val s = ContextOverride(charging = Override(null), batteryPercent = Override(null), minutesToNextEvent = Override(null)).apply(real)
        assertNull(s.charging); assertNull(s.batteryPercent); assertNull(s.nextEvent)
    }

    @Test
    fun `a made-up next event is relative to the faked clock`() {
        val s = ContextOverride(now = LocalDateTime.parse("2026-10-27T08:00"), minutesToNextEvent = Override(10)).apply(real)
        assertEquals(LocalDateTime.parse("2026-10-27T08:10"), s.nextEvent?.start)
    }

    @Test
    fun `lists replace rather than merge`() {
        val s = ContextOverride(activeModes = emptyList(), bluetoothDevices = listOf("Car")).apply(real)
        assertEquals(emptyList(), s.activeModes)
        assertEquals(listOf("Car"), s.bluetoothDevices)
    }

    @Test
    fun `it survives json`() {
        val o = ContextOverride(now = LocalDateTime.parse("2026-10-27T07:30"), charging = Override(null), steps = Override(4000))
        assertEquals(o, MasalaJson.strict.decodeFromString<ContextOverride>(MasalaJson.strict.encodeToString(ContextOverride.serializer(), o)))
    }
}
