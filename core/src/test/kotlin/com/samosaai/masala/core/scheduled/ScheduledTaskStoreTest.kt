package com.samosaai.masala.core.scheduled

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** From the private prototype's `ScheduledTaskStoreTest`, over an in-memory [TaskStorage]. */
class ScheduledTaskStoreTest {
    private class Memory(var text: String? = null) : TaskStorage {
        override fun read() = text
        override fun write(json: String) { text = json }
    }

    private val memory = Memory()
    private val store = ScheduledTaskStore(memory)

    private fun task(id: String) = ScheduledTask(id = id, name = "Task $id", prompt = "p", startLocal = "2026-10-19T08:00")

    @Test
    fun `tasks are added, replaced and deleted`() {
        store.put(task("1"))
        store.put(task("2"))
        store.put(task("1").copy(name = "Renamed"))

        assertEquals(listOf("Renamed", "Task 2"), store.all().map { it.name })

        store.delete("1")
        assertEquals(listOf("2"), store.all().map { it.id })
    }

    @Test
    fun `update changes one task and reports a missing one`() {
        store.put(task("1"))
        val updated = store.update("1") { it.copy(enabled = false) }
        assertEquals(false, updated!!.enabled)
        assertEquals(false, store.get("1")!!.enabled)
        assertNull(store.update("nope") { it })
    }

    @Test
    fun `tasks survive a new store over the same storage`() {
        store.put(task("1").copy(skillId = "chef", repeat = Repeat(RepeatKind.WEEKLY, weekdays = listOf(1, 3))))
        val again = ScheduledTaskStore(memory).get("1")!!
        assertEquals("chef", again.skillId)
        assertEquals(Repeat(RepeatKind.WEEKLY, weekdays = listOf(1, 3)), again.repeat)
    }

    @Test
    fun `tasks stored before the note link load without one and keep a new one`() {
        memory.text = """[{"id":"1","name":"Old","prompt":"p","startLocal":"2026-10-19T08:00"}]"""
        assertNull(store.get("1")!!.memoryNote)
        store.update("1") { it.copy(memoryNote = "notes/dentist.md") }
        assertEquals("notes/dentist.md", ScheduledTaskStore(memory).get("1")!!.memoryNote)
    }

    @Test
    fun `tasks stored before the run link load without one and keep a new one`() {
        memory.text = """[{"id":"1","name":"Old","prompt":"p","startLocal":"2026-10-19T08:00","history":[{"at":1,"status":"DONE"}]}]"""
        assertNull(store.get("1")!!.history.single().skillRunId)
        store.update("1") { it.withRun(TaskRun(at = 2, status = TaskRunStatus.DONE, skillRunId = 9)) }
        assertEquals(9, ScheduledTaskStore(memory).get("1")!!.history.last().skillRunId)
    }

    @Test
    fun `unreadable storage reads as no tasks`() {
        memory.text = "{not json"
        assertEquals(emptyList(), store.all())
    }
}
