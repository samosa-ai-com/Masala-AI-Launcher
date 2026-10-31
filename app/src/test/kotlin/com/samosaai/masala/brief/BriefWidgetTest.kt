package com.samosaai.masala.brief

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BriefWidgetTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 10, 31)
    private fun at(h: Int, m: Int = 0, d: LocalDate = day) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun due(
        runId: Long = 7,
        status: String = "done",
        finishedAt: Long? = at(7, 31),
        now: Long = at(8, 5),
        readAloud: Boolean = true,
        quiet: Boolean = false,
        lastRead: Long = 6,
    ) = BriefWidget.autoReadDue(runId, status, finishedAt, now, zone, readAloud, quiet, lastRead)

    @Test
    fun `a fresh brief is read once on the first look before noon`() {
        assertTrue(due())
        assertFalse(due(lastRead = 7), "already read")
        assertFalse(due(now = at(12, 0)), "afternoon")
        assertFalse(due(finishedAt = at(7, 31, day.minusDays(1))), "yesterday's")
    }

    @Test
    fun `nothing is read when the user would not want it`() {
        assertFalse(due(readAloud = false))
        assertFalse(due(quiet = true))
        assertFalse(due(status = "failed"))
        assertFalse(due(status = "running", finishedAt = null))
    }

    @Test
    fun `the run label says how old a result is`() {
        val today = BriefWidget.runLabel(at(7, 31), "Today", "Yesterday", zone, day)
        assertTrue(today.startsWith("Today "), today)
        assertTrue(BriefWidget.runLabel(at(7, 31, day.minusDays(1)), "Today", "Yesterday", zone, day).startsWith("Yesterday "))
        val old = BriefWidget.runLabel(at(7, 31, day.minusDays(3)), "Today", "Yesterday", zone, day)
        assertFalse(old.startsWith("Today") || old.startsWith("Yesterday"), old)
        assertTrue("28" in old, old)
    }

    @Test
    fun `a medium widget previews the first section`() {
        assertEquals("**Today**\n- 09:30 Standup", BriefWidget.preview("\n**Today**\n- 09:30 Standup\n\n**Weather**\nRain\n"))
        assertEquals("", BriefWidget.preview("   "))
    }

    @Test
    fun `labels on single lines become paragraphs, so the preview is the first section`() {
        val md = "**Today**: Nothing on the calendar.\n**Weather**: Rain.\n**Worth knowing**:\n- **Big** news"
        assertEquals("**Today**: Nothing on the calendar.\n\n**Weather**: Rain.\n\n**Worth knowing**:\n\n- **Big** news".replace("\n\n- ", "\n- "), BriefWidget.paragraphs(md))
        assertEquals("**Today**: Nothing on the calendar.", BriefWidget.preview(md))
    }

    @Test
    fun `only a real silencing counts as quiet`() {
        val normal = android.media.AudioManager.RINGER_MODE_NORMAL
        val all = android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        assertFalse(BriefWidget.isQuiet(normal, all))
        assertFalse(BriefWidget.isQuiet(normal, android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN), "unknown filter")
        assertFalse(BriefWidget.isQuiet(null, null), "nothing could be read")
        assertTrue(BriefWidget.isQuiet(android.media.AudioManager.RINGER_MODE_SILENT, all))
        assertTrue(BriefWidget.isQuiet(android.media.AudioManager.RINGER_MODE_VIBRATE, all))
        assertTrue(BriefWidget.isQuiet(normal, android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(BriefWidget.isQuiet(normal, android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertTrue(BriefWidget.isQuiet(normal, android.app.NotificationManager.INTERRUPTION_FILTER_NONE))
    }
}
