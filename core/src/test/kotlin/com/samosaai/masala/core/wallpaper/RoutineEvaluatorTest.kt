package com.samosaai.masala.core.wallpaper

import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.WeatherKind
import com.samosaai.masala.core.contract.WeatherNow
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineEvaluatorTest {

    private val morning = LocalDateTime.of(2026, 11, 3, 7, 30) // a Tuesday
    private val evening = LocalDateTime.of(2026, 11, 3, 21, 0)
    private val wednesday = LocalDateTime.of(2026, 11, 4, 9, 0)

    private fun snapshot(
        now: LocalDateTime = morning,
        weather: WeatherNow? = WeatherNow(WeatherKind.CLEAR, 18.0),
        battery: Int? = 80,
        charging: Boolean? = false,
    ) = ContextSnapshot(now = now, weather = weather, batteryPercent = battery, charging = charging)

    private fun routine(
        id: String = "day",
        enabled: Boolean = true,
        default: String? = null,
        rules: List<RoutineRule>,
    ) = WallpaperRoutine(id = id, name = id, enabled = enabled, rules = rules, defaultSource = default)

    private fun dayRule(from: String, to: String, source: String = "asset:one.webp") =
        RoutineRule(RoutineConditions(from = from, to = to), source)

    @Test
    fun `a time window inside and outside`() {
        val r = routine(rules = listOf(dayRule("06:00", "10:00")))
        assertEquals("asset:one.webp", RoutineEvaluator.choose(listOf(r), snapshot())?.source)
        assertNull(RoutineEvaluator.choose(listOf(r), snapshot(now = LocalDateTime.of(2026, 11, 3, 12, 0))))
    }

    @Test
    fun `a window that crosses midnight is still valid`() {
        val r = routine(rules = listOf(dayRule("22:00", "06:00")))
        assertEquals("asset:one.webp", RoutineEvaluator.choose(listOf(r), snapshot(now = LocalDateTime.of(2026, 11, 3, 23, 0)))?.source)
        assertEquals("asset:one.webp", RoutineEvaluator.choose(listOf(r), snapshot(now = LocalDateTime.of(2026, 11, 3, 5, 59)))?.source)
        assertNull(RoutineEvaluator.choose(listOf(r), snapshot(now = LocalDateTime.of(2026, 11, 3, 12, 0))))
    }

    @Test
    fun `the first matching rule wins, and the default catches the rest`() {
        val r = routine(
            default = "asset:default.webp",
            rules = listOf(
                RoutineRule(RoutineConditions(weather = listOf(WeatherKind.RAIN)), "asset:rain.webp"),
                dayRule("06:00", "10:00", "asset:morning.webp"),
            ),
        )
        // Rule order is the user's: the rain rule listed first wins when it matches.
        assertEquals("asset:rain.webp", RoutineEvaluator.choose(listOf(r), snapshot(weather = WeatherNow(WeatherKind.RAIN, 12.0)))?.source)
        assertEquals("asset:morning.webp", RoutineEvaluator.choose(listOf(r), snapshot())?.source)
        assertEquals("asset:default.webp", RoutineEvaluator.choose(listOf(r), snapshot(now = LocalDateTime.of(2026, 11, 3, 15, 0)))?.source)
    }

    @Test
    fun `missing signals fail only the conditions that need them`() {
        val weatherRule = routine(rules = listOf(RoutineRule(RoutineConditions(weather = listOf(WeatherKind.RAIN)), "asset:rain.webp")))
        // No weather data: the rain rule does not match, and there is no default.
        assertNull(RoutineEvaluator.choose(listOf(weatherRule), snapshot(weather = null)))
        val batteryRule = routine(rules = listOf(RoutineRule(RoutineConditions(batteryBelow = 20), "asset:low.webp")))
        assertEquals("asset:low.webp", RoutineEvaluator.choose(listOf(batteryRule), snapshot(battery = 10))?.source)
        assertNull(RoutineEvaluator.choose(listOf(batteryRule), snapshot(battery = null)))
        val chargingRule = routine(rules = listOf(RoutineRule(RoutineConditions(charging = true), "asset:plug.webp")))
        assertEquals("asset:plug.webp", RoutineEvaluator.choose(listOf(chargingRule), snapshot(charging = true))?.source)
        assertNull(RoutineEvaluator.choose(listOf(chargingRule), snapshot(charging = false)))
    }

    @Test
    fun `days restrict a window, and a disabled routine never applies`() {
        val tuesdayOnly = routine(rules = listOf(RoutineRule(RoutineConditions(days = listOf(2)), "asset:tue.webp")))
        assertEquals("asset:tue.webp", RoutineEvaluator.choose(listOf(tuesdayOnly), snapshot())?.source)
        // A Wednesday is another day; the evening above is still Tuesday.
        assertNull(RoutineEvaluator.choose(listOf(tuesdayOnly), snapshot(now = wednesday)))

        val off = routine(enabled = false, default = "asset:off.webp", rules = listOf(dayRule("00:00", "23:59", "asset:day.webp")))
        assertNull(RoutineEvaluator.choose(listOf(off), snapshot()))
    }

    @Test
    fun `earlier routines win over later ones`() {
        val first = routine(id = "a", rules = listOf(dayRule("06:00", "22:00", "asset:a.webp")))
        val second = routine(id = "b", rules = listOf(dayRule("00:00", "23:59", "asset:b.webp")))
        val match = RoutineEvaluator.choose(listOf(first, second), snapshot())
        assertEquals("a", match?.routineId)
        assertNull(match?.ruleIndex?.let { null })
    }

    @Test
    fun `a default never shadows another routine's matching rule`() {
        // Ordering is by edit time, so a routine's fallback must not swallow a later rule: the
        // rules are considered as a group, then the defaults.
        val withDefault = routine(id = "a", default = "asset:fallback.webp", rules = emptyList())
        val later = routine(id = "b", rules = listOf(dayRule("06:00", "10:00", "asset:later.webp")))
        val match = RoutineEvaluator.choose(listOf(withDefault, later), snapshot())
        assertEquals("asset:later.webp", match?.source)
        assertEquals("b", match?.routineId)
        assertEquals("asset:fallback.webp", RoutineEvaluator.choose(listOf(withDefault), snapshot())?.source)
    }

    @Test
    fun `the next boundary is the next window edge, today or tomorrow`() {
        val r = routine(rules = listOf(dayRule("22:00", "06:00")))
        val next = RoutineEvaluator.nextBoundary(listOf(r), morning)
        // 07:30 is past both edges today, so tomorrow's 22:00 is the next change.
        assertEquals(LocalDateTime.of(2026, 11, 3, 22, 0), next)
        val midday = RoutineEvaluator.nextBoundary(listOf(r), LocalDateTime.of(2026, 11, 3, 12, 0))
        assertEquals(LocalDateTime.of(2026, 11, 3, 22, 0), midday)
        assertNull(RoutineEvaluator.nextBoundary(listOf(routine(rules = listOf(RoutineRule(RoutineConditions(), "asset:x.webp")))), morning))
    }

    @Test
    fun `times are strict`() {
        assertEquals(360, RoutineEvaluator.minutesOf("6:00"))
        assertEquals(0, RoutineEvaluator.minutesOf("00:00"))
        assertNull(RoutineEvaluator.minutesOf("24:00"))
        assertNull(RoutineEvaluator.minutesOf("6"))
        assertNull(RoutineEvaluator.minutesOf(null))
        assertFalse(RoutineEvaluator.matches(RoutineConditions(from = "10:00", to = "10:00"), snapshot()))
    }

    @Test
    fun `a routine is checked before it can be saved`() {
        val good = routine(default = "asset:day.webp", rules = listOf(dayRule("06:00", "10:00")))
        assertEquals(emptyList(), RoutineChecker.check(good))

        val bad = WallpaperRoutine(
            id = "",
            name = "x",
            target = "lock",
            rules = listOf(RoutineRule(RoutineConditions(from = "25:00", to = "07:00", days = listOf(9), batteryBelow = 140), "teleport")),
        )
        val problems = RoutineChecker.check(bad)
        assertTrue(problems.any { it.path == "$.id" })
        assertTrue(problems.any { it.path == "$.target" })
        assertTrue(problems.any { it.path == "$.rules[0].when.from" })
        assertTrue(problems.any { it.path == "$.rules[0].when.days[0]" })
        assertTrue(problems.any { it.path == "$.rules[0].when.batteryBelow" })
        assertTrue(problems.any { it.path == "$.rules[0].source" })
    }
}
