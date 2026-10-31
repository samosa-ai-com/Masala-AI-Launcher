package com.samosaai.masala.secondbrain

import com.samosaai.masala.agent.MemoryTools
import com.samosaai.masala.core.scheduled.ScheduledTask
import com.samosaai.masala.core.scheduled.ScheduledTaskStore
import com.samosaai.masala.core.scheduled.TaskStorage
import com.samosaai.masala.core.tool.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S5: a task and its note are linked both ways or not at all. */
class TaskNoteLinksTest {
    private class Memory(var text: String? = null) : TaskStorage {
        override fun read() = text
        override fun write(json: String) { text = json }
    }

    private val dir = Files.createTempDirectory("sb-links").toFile()
    private val tasks = ScheduledTaskStore(Memory())
    private val brain = SecondBrainRepository(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), SecondBrainStore(dir, today = { "2026-10-29" }))
    private val links = TaskNoteLinks(tasks, brain).also { l -> brain.onNoteDeleted = { l.noteDeleted(it) } }

    private fun note(title: String) =
        "---\ntitle: $title\narea: Health\ntype: note\nupdated: 2026-10-29\n---\n\nSee [[areas/health.md]].\n"

    private fun setUp() = runBlocking {
        brain.ensure()
        brain.write("notes/dentist.md", note("Dentist"))
        brain.write("notes/physio.md", note("Physio"))
        // The hub links back, so the notes are not islands.
        brain.edit("areas/health.md", "# Health", "# Health\n\n[[notes/dentist.md]] [[notes/physio.md]]")
        tasks.put(ScheduledTask(id = "t1", name = "Dentist reminder", prompt = "p", startLocal = "2026-11-02T09:00"))
        tasks.put(ScheduledTask(id = "t2", name = "Physio reminder", prompt = "p", startLocal = "2026-11-03T09:00"))
    }

    private fun text(path: String) = File(dir, path).readText()

    @Test fun linkSetsBothSidesAndHealthIsClean() = runBlocking<Unit> {
        setUp()
        assertTrue(links.link("Dentist reminder", "notes/dentist.md").ok)
        assertEquals("notes/dentist.md", tasks.get("t1")!!.memoryNote)
        assertTrue(text("notes/dentist.md").contains("task_ref: t1"))
        val health = brain.health(links.healthLinks())
        assertTrue(health.isClean, health.toString())
    }

    @Test fun relinkingClearsTheOldSides() = runBlocking<Unit> {
        setUp()
        links.link("t1", "notes/dentist.md")
        links.link("t1", "notes/physio.md")
        assertFalse(text("notes/dentist.md").contains("task_ref"))
        assertTrue(text("notes/physio.md").contains("task_ref: t1"))
        // Another task takes the note over: the first one lets go.
        links.link("t2", "notes/physio.md")
        assertNull(tasks.get("t1")!!.memoryNote)
        assertTrue(text("notes/physio.md").contains("task_ref: t2"))
        assertEquals(1, Regex("task_ref").findAll(text("notes/physio.md")).count())
        assertTrue(brain.health(links.healthLinks()).isClean)
    }

    @Test fun deletingEitherSideClearsTheOther() = runBlocking<Unit> {
        setUp()
        links.link("t1", "notes/dentist.md")
        links.taskDeleted(tasks.get("t1")!!)
        tasks.delete("t1")
        assertFalse(text("notes/dentist.md").contains("task_ref"))
        links.link("t2", "notes/physio.md")
        brain.edit("areas/health.md", " [[notes/physio.md]]", "")
        assertTrue(brain.remove("notes/physio.md").ok)
        assertNull(tasks.get("t2")!!.memoryNote)
        assertTrue(brain.health(links.healthLinks()).isClean)
    }

    @Test fun healthSeesOneSidedLinks() = runBlocking<Unit> {
        setUp()
        tasks.update("t1") { it.copy(memoryNote = "notes/dentist.md") }
        assertEquals(1, brain.health(links.healthLinks()).missingBacklinks.size)
        brain.edit("notes/physio.md", "type: note", "type: note\ntask_ref: gone")
        assertEquals(1, brain.health(links.healthLinks()).unknownTasks.size)
    }

    @Test fun unknownTasksAndNotesAreExplained() = runBlocking<Unit> {
        setUp()
        val unknown = links.link("Gym", "notes/dentist.md")
        assertFalse(unknown.ok)
        assertTrue(unknown.message.contains("t1 (Dentist reminder)"))
        assertFalse(links.link("t1", "notes/missing.md").ok)
        assertTrue(links.unlink("t1").message.contains("no linked note"))
    }

    @Test fun theToolLinksAndUnlinks() = runBlocking<Unit> {
        setUp()
        val tool = MemoryTools.linkTask(links)
        fun args(json: String) = Json.parseToJsonElement(json) as JsonObject
        assertTrue(tool.needsConfirmation(args("""{"task":"t1","path":"notes/dentist.md"}""")))
        assertTrue(tool.execute(args("""{"task":"t1","path":"notes/dentist.md"}""")) is ToolResult.Success)
        assertTrue(tool.execute(args("""{"task":"t1","path":""}""")) is ToolResult.Success)
        assertNull(tasks.get("t1")!!.memoryNote)
        assertFalse(text("notes/dentist.md").contains("task_ref"))
    }

    @Test fun clearingWorksOnABrokenNote() = runBlocking<Unit> {
        setUp()
        File(dir, "notes/broken.md").writeText("---\ntitle: Broken\ntask_ref: t1\n---\n\nX.\n")
        assertTrue(brain.clearTaskRef("notes/broken.md"))
        assertFalse(text("notes/broken.md").contains("task_ref"))
        assertFalse(brain.linkTask("notes/broken.md", "t1").ok)
    }
}
