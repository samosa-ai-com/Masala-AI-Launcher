package com.samosaai.masala.secondbrain

import com.samosaai.masala.core.scheduled.ScheduledTask
import com.samosaai.masala.core.scheduled.ScheduledTaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps scheduled tasks and Second Brain notes linked both ways or not at all (plan
 * `docs/plans/second-brain-robust.md`, S5): the task carries [ScheduledTask.memoryNote], the note carries
 * `task_ref`. Deleting either side clears the other.
 */
class TaskNoteLinks(
    private val tasks: ScheduledTaskStore,
    private val brain: SecondBrainRepository,
) {
    /** What a link request did, as text the agent can pass on. */
    data class Outcome(val ok: Boolean, val message: String)

    /** Links the task named by id or name to [path], replacing either side's earlier link. */
    suspend fun link(task: String, path: String): Outcome {
        val found = find(task) ?: return Outcome(false, unknownTask(task))
        val note = path.trim().trimStart('/')
        if (brain.readPath(note) == null) return Outcome(false, "No Second Brain note at '$note'.")
        brain.taskRefOf(note)?.takeIf { it != found.id }?.let { other ->
            withContext(Dispatchers.IO) { tasks.update(other) { it.copy(memoryNote = null) } }
        }
        found.memoryNote?.takeIf { it != note }?.let { brain.clearTaskRef(it) }
        val written = brain.linkTask(note, found.id)
        if (!written.ok) return Outcome(false, "Not linked:\n" + written.errors.joinToString("\n") { "- $it" })
        withContext(Dispatchers.IO) { tasks.update(found.id) { it.copy(memoryNote = note) } }
        return Outcome(true, "Linked task '${found.name}' (${found.id}) and $note both ways.")
    }

    /** Removes the task's link on both sides. */
    suspend fun unlink(task: String): Outcome {
        val found = find(task) ?: return Outcome(false, unknownTask(task))
        val note = found.memoryNote ?: return Outcome(true, "Task '${found.name}' has no linked note.")
        if (brain.taskRefOf(note) == found.id) brain.clearTaskRef(note)
        withContext(Dispatchers.IO) { tasks.update(found.id) { it.copy(memoryNote = null) } }
        return Outcome(true, "Unlinked task '${found.name}' from $note.")
    }

    /** Before a task is deleted: its note loses the `task_ref` that names it. */
    suspend fun taskDeleted(task: ScheduledTask) {
        val note = task.memoryNote ?: return
        if (brain.taskRefOf(note) == task.id) brain.clearTaskRef(note)
    }

    /** After a note is deleted: tasks pointing at it let go. */
    suspend fun noteDeleted(path: String) {
        withContext(Dispatchers.IO) {
            tasks.all().filter { it.memoryNote == path }.forEach { task -> tasks.update(task.id) { it.copy(memoryNote = null) } }
        }
    }

    /** Every task as the health check sees it. */
    suspend fun healthLinks(): List<TaskLink> = withContext(Dispatchers.IO) {
        tasks.all().map { TaskLink(it.id, it.name, it.memoryNote) }
    }

    private suspend fun find(task: String): ScheduledTask? = withContext(Dispatchers.IO) {
        val all = tasks.all()
        all.firstOrNull { it.id == task.trim() } ?: all.singleOrNull { it.name.equals(task.trim(), ignoreCase = true) }
    }

    private suspend fun unknownTask(task: String): String {
        val all = withContext(Dispatchers.IO) { tasks.all() }
        return if (all.isEmpty()) {
            "There are no scheduled tasks to link."
        } else {
            "No single task matches '$task'. Tasks: " + all.joinToString("; ") { "${it.id} (${it.name})" }
        }
    }
}
