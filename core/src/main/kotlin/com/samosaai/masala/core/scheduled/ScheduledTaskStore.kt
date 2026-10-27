package com.samosaai.masala.core.scheduled

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Where the store keeps its one JSON string; `:app` backs it with SharedPreferences. */
interface TaskStorage {
    fun read(): String?

    /** Must be durable on return: the alarm receiver writes just before its process may go. */
    fun write(json: String)
}

/**
 * From the private prototype's `ScheduledTaskStore`, without the stored files and the chat handling, and over a
 * [TaskStorage] instead of encrypted preferences so it runs on the JVM. The tasks live in app-private
 * storage, which is encrypted at rest by Android.
 *
 * Every change goes through [update], which holds one lock across the read and the write, so the
 * alarm receiver and the Settings page can't overwrite each other's changes.
 */
class ScheduledTaskStore(private val storage: TaskStorage) {

    private val json = Json { ignoreUnknownKeys = true }
    private val tasksSerializer = ListSerializer(ScheduledTask.serializer())

    /** Oldest first. */
    fun all(): List<ScheduledTask> = synchronized(LOCK) { read() }

    fun get(id: String): ScheduledTask? = all().firstOrNull { it.id == id }

    /**
     * Replaces the task [id] with what [transform] makes of it (null deletes it) and returns the
     * result. Returns null, changing nothing, when there is no such task.
     */
    fun update(id: String, transform: (ScheduledTask) -> ScheduledTask?): ScheduledTask? = synchronized(LOCK) {
        val tasks = read()
        val current = tasks.firstOrNull { it.id == id } ?: return null
        val changed = transform(current)
        write(if (changed == null) tasks.filterNot { it.id == id } else tasks.map { if (it.id == id) changed else it })
        changed
    }

    /** Adds [task], or replaces the one with its id. */
    fun put(task: ScheduledTask) = synchronized(LOCK) {
        val tasks = read()
        write(if (tasks.any { it.id == task.id }) tasks.map { if (it.id == task.id) task else it } else tasks + task)
    }

    fun delete(id: String) {
        update(id) { null }
    }

    private fun read(): List<ScheduledTask> =
        storage.read()
            ?.let { runCatching { json.decodeFromString(tasksSerializer, it) }.getOrNull() }
            .orEmpty()

    private fun write(tasks: List<ScheduledTask>) {
        storage.write(json.encodeToString(tasksSerializer, tasks))
    }

    private companion object {
        val LOCK = Any()
    }
}
