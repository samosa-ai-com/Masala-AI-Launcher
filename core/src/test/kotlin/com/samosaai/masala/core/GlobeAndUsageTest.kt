package com.samosaai.masala.core

import com.samosaai.masala.core.apps.GlobeLayout
import com.samosaai.masala.core.apps.UsageRange
import com.samosaai.masala.core.apps.UsageRank
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobeAndUsageTest {
    // The design sample's 7-day counts for 25 apps.
    private val week = listOf(2, 6, 14, 4, 95, 9, 28, 40, 15, 1, 8, 5, 3, 7, 22, 26, 30, 35, 120, 70, 18, 50, 10, 2, 12)

    @Test
    fun `the same counts always give the same globe`() {
        assertEquals(GlobeLayout.layout(week), GlobeLayout.layout(week.toList()))
    }

    @Test
    fun `the most used is biggest and in the middle`() {
        val circles = GlobeLayout.layout(week)
        val top = week.indexOf(week.max())
        assertEquals(circles.maxOf { it.r }, circles[top].r)
        assertEquals(GlobeLayout.CENTER, circles[top].x, 1e-9)
        assertEquals(GlobeLayout.CENTER, circles[top].y, 1e-9)
        // Bigger counts never get smaller circles.
        val byCount = week.indices.sortedBy { week[it] }
        byCount.zipWithNext().forEach { (a, b) -> assertTrue(circles[a].r <= circles[b].r + 1e-9) }
    }

    @Test
    fun `circles stay inside the globe and keep the gap`() {
        for (counts in listOf(week, List(30) { 1 }, List(30) { if (it == 0) 500 else 1 }, (1..30).toList())) {
            val c = GlobeLayout.layout(counts)
            c.forEach { assertTrue(hypot(it.x - GlobeLayout.CENTER, it.y - GlobeLayout.CENTER) + it.r <= GlobeLayout.GLOBE_RADIUS + 1e-6, "outside: $it") }
            for (i in c.indices) for (j in i + 1 until c.size) {
                val d = hypot(c[i].x - c[j].x, c[i].y - c[j].y)
                assertTrue(d >= c[i].r + c[j].r + GlobeLayout.GAP - 1e-6, "overlap $i/$j for $counts")
            }
        }
    }

    @Test
    fun `the circles fill about half the globe`() {
        val area = GlobeLayout.diameters(week).sumOf { it * it } * PI / 4
        val globe = PI * GlobeLayout.GLOBE_RADIUS * GlobeLayout.GLOBE_RADIUS
        assertEquals(GlobeLayout.FILL, area / globe, 0.01)
    }

    @Test
    fun `thirty apps pack fast`() {
        val counts = (1..30).map { it * 3 }
        GlobeLayout.layout(counts)
        val start = System.nanoTime()
        repeat(10) { GlobeLayout.layout(counts) }
        val ms = (System.nanoTime() - start) / 1e6 / 10
        assertTrue(ms < 20, "took $ms ms")
    }

    @Test
    fun `ranks by count in the range, ties by label, never-opened left out`() {
        val day = 24 * 60 * 60 * 1000L
        val now = 100 * day
        val launches = listOf(
            UsageRank.Launch("maps", now - 1), UsageRank.Launch("maps", now - 2),
            UsageRank.Launch("chrome", now - 3), UsageRank.Launch("camera", now - 4),
            UsageRank.Launch("chrome", now - 10 * day),
        )
        val since = UsageRange.WEEK.since(now, ZoneOffset.UTC)
        val counts = UsageRank.counts(launches, since)
        val ranked = UsageRank.top(counts, listOf("maps", "chrome", "camera", "clock"), { it })
        assertEquals(listOf("maps" to 2, "camera" to 1, "chrome" to 1), ranked.map { it.key to it.count })
        assertEquals(2, UsageRank.counts(launches, UsageRange.MONTH.since(now, ZoneOffset.UTC))["chrome"])
    }

    @Test
    fun `today starts at local midnight`() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = LocalDateTime.of(2026, 10, 30, 1, 30).atZone(zone).toInstant().toEpochMilli()
        val midnight = LocalDateTime.of(2026, 10, 30, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(midnight, UsageRange.TODAY.since(now, zone))
    }

    @Test
    fun `suggested now follows the time of day, across midnight too`() {
        val zone = ZoneOffset.UTC
        fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, day, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
        val launches = listOf(
            UsageRank.Launch("gym", at(20, 18, 0)), UsageRank.Launch("gym", at(21, 18, 20)),
            UsageRank.Launch("music", at(22, 17, 50)), UsageRank.Launch("mail", at(22, 9, 0)),
            UsageRank.Launch("alarm", at(22, 23, 50)),
        )
        assertEquals(listOf("gym", "music"), UsageRank.suggestedNow(launches, 18 * 60, zone, { it }))
        assertEquals(listOf("alarm"), UsageRank.suggestedNow(launches, 0, zone, { it }))
    }
}
