package com.samosaai.masala.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The experts consulted inside one chat, one JSON file per expert under `consults/<chatId>/`.
 * Deliberately separate from [ChatSessions]: a consult is not a conversation the user owns, it is
 * never listed or searched, and it is removed with its chat (see `ChatSessions.onDeleted`).
 *
 * Ids are the chat id plus a slot (`<chatId>-c1`), so a consult cannot be confused with another
 * chat's and a slot freed by a delete can be reused.
 */
class ConsultSessions(
    private val root: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads and writes arrive from one turn at a time but the tool can be called back to back. */
    private val lock = Mutex()

    /** The consults of [chatId], oldest first. Unknown chat → empty. */
    suspend fun list(chatId: String): List<ConsultSession> = lock.withLock { withContext(Dispatchers.IO) {
        readAll(chatId)
    } }

    /** [list] without the mutex, for callers already holding it. */
    private fun readAll(chatId: String): List<ConsultSession> {
        if (!safe(chatId)) return emptyList()
        return folder(chatId).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString(ConsultSession.serializer(), file.readText()) }.getOrNull()
                    // A file whose name disagrees with its id is stale; drop it rather than resurface it.
                    ?.takeIf { it.id + ".json" == file.name && it.chatId == chatId }
            }
            .sortedBy { it.createdAt }
    }

    /** One consult, or null when it is not this chat's. */
    suspend fun get(chatId: String, consultId: String): ConsultSession? = lock.withLock { withContext(Dispatchers.IO) {
        if (!safe(chatId) || !safe(consultId) || !consultId.startsWith("$chatId-c")) return@withContext null
        runCatching {
            val file = fileFor(chatId, consultId)
            if (!file.isFile) null
            else json.decodeFromString(ConsultSession.serializer(), file.readText()).takeIf { it.chatId == chatId }
        }.getOrNull()
    } }

    /** The most recent consult with [skillId], for continuing with the same expert. */
    suspend fun newest(chatId: String, skillId: String): ConsultSession? =
        list(chatId).lastOrNull { it.skillId == skillId }

    /**
     * Starts a consult, in the lowest free slot. Null when [chatId] already has [MAX_PER_CHAT]
     * consults — the caller reports that to the agent rather than silently refusing.
     */
    suspend fun create(chatId: String, skillId: String, title: String): ConsultSession? =
        lock.withLock { withContext(Dispatchers.IO) {
            if (!safe(chatId)) return@withContext null
            val used = readAll(chatId).mapTo(HashSet()) { it.id }
            val slot = (1..MAX_PER_CHAT).firstOrNull { "$chatId-c$it" !in used } ?: return@withContext null
            val now = clock()
            ConsultSession(
                id = "$chatId-c$slot",
                chatId = chatId,
                skillId = skillId,
                title = title,
                createdAt = now,
                updatedAt = now,
            ).also { write(it) }
        } }

    /**
     * Adds [turn] to a consult. Null when the consult is gone, belongs to another chat, or the
     * consult already has [MAX_EXCHANGES] questions and this is another one.
     */
    suspend fun append(chatId: String, consultId: String, turn: ConsultTurn): ConsultSession? =
        lock.withLock { withContext(Dispatchers.IO) {
            val session = read(chatId, consultId) ?: return@withContext null
            if (turn.role == TurnRole.USER && session.exchanges >= MAX_EXCHANGES) return@withContext null
            val next = session.copy(
                turns = session.turns + turn.copy(at = turn.at.takeIf { it > 0 } ?: clock()),
                updatedAt = clock(),
            )
            write(next)
            next
        } }

    /** Removes every consult of [chatId]; the chat itself is being deleted. */
    suspend fun deleteChat(chatId: String) = lock.withLock { withContext(Dispatchers.IO) {
        if (!safe(chatId)) return@withContext
        folder(chatId).deleteRecursively()
    } }

    private fun read(chatId: String, consultId: String): ConsultSession? = runCatching {
        if (!safe(chatId) || !safe(consultId) || !consultId.startsWith("$chatId-c")) return null
        val file = fileFor(chatId, consultId)
        if (!file.isFile) null
        else json.decodeFromString(ConsultSession.serializer(), file.readText()).takeIf { it.chatId == chatId }
    }.getOrNull()

    private fun write(session: ConsultSession) {
        val dir = folder(session.chatId)
        dir.mkdirs()
        // Temp file + rename: a process killed mid-write must never leave a truncated consult.
        runCatching {
            val target = fileFor(session.chatId, session.id)
            val temp = File(dir, "${session.id}.json.tmp")
            temp.writeText(json.encodeToString(ConsultSession.serializer(), session))
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private fun folder(chatId: String) = File(root, chatId)

    private fun fileFor(chatId: String, consultId: String) = File(folder(chatId), "$consultId.json")

    /** Ids are generated by this class and passed back by a model: never let one become a path. */
    private fun safe(id: String) = id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    companion object {
        /** The folder under the app's files directory. */
        const val DIR = "consults"

        /** Experts one chat may consult; past this the agent is told to answer in the main chat. */
        const val MAX_PER_CHAT = 10

        /** Questions one consult may ask; past this it must be continued in the main chat. */
        const val MAX_EXCHANGES = 10
    }
}
