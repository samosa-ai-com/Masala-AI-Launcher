package com.samosaai.masala.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Conversations on disk: one JSON file per session under [dir], newest first in
 * [summaries]. Small enough to rewrite whole on every turn; bounded so a long-lived
 * install keeps at most [MAX_SESSIONS] conversations of [MAX_TURNS] turns each.
 *
 * Pure file work, no Android types, so the store is unit-tested on the JVM.
 */
class ChatSessions(
    private val dir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Told when a conversation is removed, so what belongs to it (its consults) goes too. Awaited, so a kill can't leave it behind. */
    private val onDeleted: suspend (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _summaries = MutableStateFlow<List<ChatSummary>>(emptyList())
    val summaries: StateFlow<List<ChatSummary>> = _summaries.asStateFlow()

    private val loaded = mutableMapOf<String, ChatSession>()

    /** Appends and deletes arrive from separate coroutines; file work must not interleave. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Reads every session from disk once; the app calls this at start. */
    suspend fun load(): List<ChatSummary> = lock.withLock { withContext(Dispatchers.IO) {
        dir.mkdirs()
        loaded.clear()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString(ChatSession.serializer(), file.readText()) }.getOrNull()
                    // A file whose name disagrees with its id is stale; drop it rather than resurface it.
                    ?.takeIf { it.id + ".json" == file.name }
                    ?.also { loaded[it.id] = it }
            }
            .sortedByDescending { it.updatedAt }
            .also { prune() }
            .also { publish() }
            .map { it.summary() }
    } }

    /** The session with [id] as last written, or null when it is not on disk. */
    suspend fun get(id: String): ChatSession? = lock.withLock { withContext(Dispatchers.IO) {
        loaded[id] ?: read(id).also { if (it != null) loaded[id] = it }
    } }

    /** The newest conversation, for resuming when the app opens. */
    fun latest(): ChatSummary? = _summaries.value.firstOrNull()

    /** A new, empty conversation; it appears in [summaries] only once it has a turn. */
    suspend fun create(): ChatSession = lock.withLock { withContext(Dispatchers.IO) {
        val now = clock()
        ChatSession(id = newId(), createdAt = now, updatedAt = now).also { loaded[it.id] = it }
    } }

    /**
     * Adds [turn] to [id], titles the session from its first user words, and writes it.
     * Returns the updated session, or null when it no longer exists.
     */
    suspend fun append(id: String, turn: ChatTurn): ChatSession? = lock.withLock { withContext(Dispatchers.IO) {
        val session = loaded[id] ?: read(id) ?: return@withContext null
        val turns = (session.turns + turn.copy(at = turn.at.takeIf { it > 0 } ?: clock())).takeLast(MAX_TURNS)
        val title = session.title.ifBlank {
            if (turn.role == TurnRole.USER) turn.text.trim().take(48).ifBlank { "" } else ""
        }
        val next = session.copy(turns = turns, title = title, updatedAt = clock())
        loaded[id] = next
        write(next)
        // New conversations age old ones out here too, not only on the next launch.
        prune()
        publish()
        next
    } }

    /** Retitles a conversation; a blank title falls back to the first user words. */
    suspend fun rename(id: String, title: String): ChatSession? = lock.withLock { withContext(Dispatchers.IO) {
        val session = loaded[id] ?: read(id) ?: return@withContext null
        val next = session.copy(
            title = title.trim().take(60).ifBlank {
                session.turns.firstOrNull { it.role == TurnRole.USER }?.text?.trim()?.take(48).orEmpty()
            },
            updatedAt = clock(),
        )
        loaded[id] = next
        write(next)
        publish()
        next
    } }

    /** Removes a conversation, its file and what belongs to it. */
    suspend fun delete(id: String) = lock.withLock { withContext(Dispatchers.IO) {
        loaded.remove(id)
        fileFor(id).delete()
        onDeleted(id)
        publish()
    } }

    /**
     * Reverts the conversation to the user message at [index]: drops that
     * message and everything after it, so its text can be edited and resent.
     * Unknown ids and out-of-range indices return null without writing.
     */
    suspend fun revertTo(id: String, index: Int): ChatSession? = lock.withLock { withContext(Dispatchers.IO) {
        val session = loaded[id] ?: read(id) ?: return@withContext null
        if (index !in session.turns.indices) return@withContext null
        val next = session.copy(
            turns = session.turns.take(index),
            updatedAt = clock(),
        )
        loaded[id] = next
        write(next)
        publish()
        next
    } }

    private fun publish() {
        _summaries.value = loaded.values.sortedByDescending { it.updatedAt }.map { it.summary() }
    }

    /** Keeps the newest [MAX_SESSIONS]; the rest and their files go. */
    private suspend fun prune() {
        // Copy first: dropping entries while iterating a live view throws.
        val extra = loaded.values.sortedByDescending { it.updatedAt }.drop(MAX_SESSIONS).toList()
        extra.forEach {
            loaded.remove(it.id)
            fileFor(it.id).delete()
            onDeleted(it.id)
        }
    }

    private fun read(id: String): ChatSession? = runCatching {
        val file = fileFor(id)
        if (!file.isFile) null else json.decodeFromString(ChatSession.serializer(), file.readText())
    }.getOrNull()

    private fun write(session: ChatSession) {
        dir.mkdirs()
        // Temp file + rename: a process killed mid-write must never leave a truncated chat.
        runCatching {
            val target = fileFor(session.id)
            val temp = File(dir, "${session.id}.json.tmp")
            temp.writeText(json.encodeToString(ChatSession.serializer(), session))
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private fun fileFor(id: String) = File(dir, "$id.json")

    private fun ChatSession.summary() = ChatSummary(
        id = id,
        title = title.ifBlank { "New chat" },
        updatedAt = updatedAt,
        preview = preview,
        turns = turns.size,
        // Bounded: enough to match a phrase the user remembers, cheap to keep in memory.
        search = (title + "\n" + turns.joinToString("\n") { it.text }).take(4_000),
    )

    companion object {
        /** Conversations kept on disk; older ones are pruned on load. */
        const val MAX_SESSIONS = 50

        /** Turns kept per conversation; the oldest are dropped. */
        const val MAX_TURNS = 200

        /** The folder under the app's files directory. */
        const val DIR = "chats"
    }
}
