package com.samosaai.masala.tools

import com.samosaai.masala.core.scheduled.ScheduledTaskStore
import com.samosaai.masala.core.scheduled.TaskStorage
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduledTaskToolsTest {
    private val zone = ZoneId.of("UTC")
    private val nowMs = LocalDateTime.of(2026, 11, 1, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private class Mem : TaskStorage {
        var json: String? = null
        var writes = 0
        override fun read(): String? = json
        override fun write(json: String) {
            this.json = json
            writes++
        }
    }

    private fun tools(mem: Mem = Mem(), skills: Set<String> = setOf("chef")) =
        ScheduledTaskTools(ScheduledTaskStore(mem), reschedule = { mem.writes++ }, skillIds = { skills }, now = { nowMs }, zone = zone)

    @Test fun `a one-time task schedules and lists`() {
        val t = tools()
        val r = t.schedule(name = "Water plants", prompt = "Remind me to water the plants", start = "2026-11-02T08:00")
        assertTrue(r.success, r.message)
        assertTrue("Water plants" in r.message)
        val listed = t.list()
        assertTrue("Water plants" in listed.message && "Mon 2 Nov 08:00" in listed.message, listed.message)
    }

    @Test fun `bad schedules fail with reasons`() {
        val t = tools()
        assertFalse(t.schedule(name = "", prompt = "x", start = "2026-11-02T08:00").success)
        assertFalse(t.schedule(name = "x", prompt = null, start = "2026-11-02T08:00").success)
        assertFalse(t.schedule(name = "x", prompt = "y", start = "someday").success)
        val past = t.schedule(name = "x", prompt = "y", start = "2026-10-01T08:00")
        assertFalse(past.success)
        assertTrue("past" in past.message)
        val weekly = t.schedule(name = "x", prompt = "y", start = "2026-11-02T08:00", repeat = "weekly")
        assertFalse(weekly.success)
        assertTrue("weekday" in weekly.message)
        val badSkill = t.schedule(name = "x", prompt = "y", start = "2026-11-02T08:00", skillId = "nope")
        assertFalse(badSkill.success)
    }

    @Test fun `weekly with days repeats`() {
        val mem = Mem()
        val r = tools(mem).schedule(
            name = "Gym", prompt = "Go", start = "2026-11-02T08:00",
            repeat = "weekly", weekdays = listOf("mon", "wed"),
        )
        assertTrue(r.success, r.message)
        assertTrue("repeats weekly" in tools(mem).list().message)
    }

    @Test fun `renaming a weekly task keeps its days`() {
        val mem = Mem()
        val t = tools(mem)
        t.schedule(
            name = "Gym", prompt = "Go", start = "2026-11-02T08:00",
            repeat = "weekly", weekdays = listOf("mon", "wed"),
        )
        val id = t.list().message.substringAfter("[").substringBefore("]")
        val renamed = t.edit(taskId = id, name = "Gym hard", repeat = "weekly")
        assertTrue(renamed.success, renamed.message)
        val edited = t.edit(taskId = id, repeat = "daily")
        assertTrue(edited.success, edited.message)
        assertTrue("repeats daily" in t.list().message)
    }

    @Test fun `edit and delete round-trip`() {
        val mem = Mem()
        val t = tools(mem)
        t.schedule(name = "Gym", prompt = "Go", start = "2026-11-02T08:00", repeat = "daily")
        val id = t.list().message.substringAfter("[").substringBefore("]")
        val renamed = t.edit(taskId = id, name = "Gym hard")
        assertTrue(renamed.success, renamed.message)
        assertTrue("Gym hard" in t.list().message)
        val paused = t.edit(taskId = id, enabled = false)
        assertTrue(paused.success)
        assertTrue("[paused]" in t.list().message)
        assertTrue(t.edit(taskId = "missing", name = "x").message.contains("No scheduled task"))
        val deleted = t.delete(id)
        assertTrue(deleted.success)
        assertTrue("No scheduled tasks." == t.list().message)
        assertTrue(t.delete(id).message.contains("Nothing to delete") || t.delete(id).message.contains("No scheduled task"))
        assertTrue(mem.writes >= 4)
    }

    @Test fun `parse helpers accept dates and weekday words`() {
        assertEquals("2026-11-02T08:00", ScheduledTaskTools.parseStart("2026-11-02T08:00"))
        assertEquals("2026-11-02T00:00", ScheduledTaskTools.parseStart("2026-11-02"))
        assertEquals(null, ScheduledTaskTools.parseStart("tomorrow"))
        assertEquals(listOf(1, 3), ScheduledTaskTools.parseWeekdays(listOf("mon, wed")))
        assertEquals(listOf(1, 2, 3, 4, 5), ScheduledTaskTools.parseWeekdays(listOf("weekdays")))
        assertEquals(null, ScheduledTaskTools.parseWeekdays(listOf("funday")))
    }
}
