package com.samosaai.masala.pro.files

import com.samosaai.masala.core.files.IndexDiff
import com.samosaai.masala.core.files.IndexRules
import com.samosaai.masala.core.files.TextExtractor
import com.samosaai.masala.pro.data.FileIndexDao
import com.samosaai.masala.pro.data.ProDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/** Where the indexer keeps what it read: the `file_index` table, or a fake in tests. */
interface IndexStore {
    suspend fun stored(): Map<String, Long>

    /** Replaces the rows for each entry's path (deletes, then adds) in one transaction. */
    suspend fun replace(entries: List<IndexedFile>)
    suspend fun delete(paths: List<String>)
    suspend fun count(): Int
    suspend fun countWithText(): Int
}

data class IndexedFile(val path: String, val name: String, val content: String, val modified: Long)

/** [IndexStore] over `:pro`'s database. */
class RoomIndexStore(private val db: ProDatabase) : IndexStore {
    private val dao: FileIndexDao get() = db.fileIndex()

    override suspend fun stored(): Map<String, Long> = dao.stored().associate { it.path to it.modified }

    override suspend fun replace(entries: List<IndexedFile>) = db.withTransaction {
        for (e in entries) {
            dao.deletePath(e.path)
            dao.insert(e.path, e.name, e.content, e.modified)
        }
    }

    override suspend fun delete(paths: List<String>) = db.withTransaction { paths.forEach { dao.deletePath(it) } }
    override suspend fun count(): Int = dao.count()
    override suspend fun countWithText(): Int = dao.countWithText()
}

/**
 * One index run over shared storage (plan Phase 3 Lane B, arch §3.10): walks [IndexRules]' folders,
 * reads only new and changed files, drops gone ones, and writes in batches. Stops between files when
 * [isStopped] says so; the next run picks up from the diff. A file that can't be read keeps its name
 * row with no text and counts as a failure.
 */
class FileIndexer(
    private val store: IndexStore,
    private val extractor: TextExtractor,
    private val batchSize: Int = BATCH,
) {
    data class Result(
        val files: Int,
        val withText: Int,
        val read: Int,
        val removed: Int,
        val failures: Int,
        /** The walk hit [IndexRules.MAX_FILES]; files past it aren't indexed. */
        val capped: Boolean,
        val stoppedEarly: Boolean,
    )

    suspend fun run(
        root: File,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isStopped: () -> Boolean = { false },
    ): Result = withContext(Dispatchers.IO) {
        val (onDisk, capped) = walk(root, isStopped)
        // A half-walked tree would make every file not reached yet look gone: change nothing.
        if (isStopped()) return@withContext Result(store.count(), store.countWithText(), 0, 0, 0, capped, stoppedEarly = true)
        val diff = IndexDiff.of(onDisk.mapValues { it.value.lastModified() }, store.stored())
        diff.gone.chunked(batchSize).forEach { store.delete(it) }

        val toRead = diff.toRead
        var failures = 0
        var done = 0
        var stopped = false
        val batch = mutableListOf<IndexedFile>()
        progress(0, toRead.size)
        for (path in toRead) {
            if (isStopped()) {
                stopped = true
                break
            }
            val file = onDisk.getValue(path)
            val (content, failed) = textOf(file)
            if (failed) failures++
            batch += IndexedFile(path, file.name, content, file.lastModified())
            done++
            if (batch.size >= batchSize) {
                store.replace(batch.toList())
                batch.clear()
                progress(done, toRead.size)
            }
        }
        if (batch.isNotEmpty()) store.replace(batch)
        progress(done, toRead.size)
        Result(store.count(), store.countWithText(), done, diff.gone.size, failures, capped, stopped)
    }

    /** The file's stored text, and whether reading it failed (unreadable formats aren't failures). */
    private fun textOf(file: File): Pair<String, Boolean> {
        if (!IndexRules.readsContent(file.name, file.length())) return "" to false
        val text = try {
            extractor.extract(file)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        return if (text == null) "" to true else IndexRules.storedText(text) to false
    }

    /** Every indexable file under [root] by path, up to [IndexRules.MAX_FILES]; and whether it stopped at that cap. */
    private fun walk(root: File, isStopped: () -> Boolean): Pair<Map<String, File>, Boolean> {
        val found = LinkedHashMap<String, File>()
        val stack = ArrayDeque<Pair<File, Int>>().apply { addLast(root to 0) }
        while (stack.isNotEmpty() && !isStopped()) {
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children.sortedBy { it.name }) {
                // Links could loop or leave storage; real files are reachable without them.
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) {
                    if (IndexRules.walksInto(child.name, depth + 1)) stack.addLast(child to depth + 1)
                } else if (child.isFile && IndexRules.indexes(child.name)) {
                    if (found.size >= IndexRules.MAX_FILES) return found to true
                    found[child.absolutePath] = child
                }
            }
        }
        return found to false
    }

    companion object {
        const val BATCH = 200
    }
}
