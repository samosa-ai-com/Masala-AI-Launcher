package com.samosaai.masala.pro.files

import com.samosaai.masala.core.files.TextExtractor
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileIndexerTest {
    private class FakeStore : IndexStore {
        val rows = linkedMapOf<String, IndexedFile>()
        var replaced = 0
        override suspend fun stored() = rows.mapValues { it.value.modified }
        override suspend fun replace(entries: List<IndexedFile>) {
            replaced += entries.size
            entries.forEach { rows[it.path] = it }
        }
        override suspend fun delete(paths: List<String>) { paths.forEach { rows.remove(it) } }
        override suspend fun count() = rows.size
        override suspend fun countWithText() = rows.values.count { it.content.isNotEmpty() }
    }

    private val root = createTempDirectory("storage").toFile()
    private val store = FakeStore()
    private val read = mutableListOf<String>()
    private val extractor = TextExtractor { file ->
        read += file.name
        if (file.name == "broken.pdf") throw IllegalStateException("bad pdf") else "text of ${file.name}"
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(path: String, text: String = "x", modified: Long = 1_000L): File =
        File(root, path).apply { parentFile.mkdirs(); writeText(text); setLastModified(modified) }

    private fun indexer() = FileIndexer(store, extractor, batchSize = 2)

    @Test
    fun `names everywhere, text from documents, skipped folders left out`() = runBlocking {
        write("Documents/lease.pdf")
        write("Documents/notes.md")
        write("Download/photo.jpg")
        write("Documents/Music/setlist.txt")
        write("DCIM/Camera/IMG_1.jpg")
        write("Android/data/x/cache.txt")
        write(".hidden/secret.txt")
        write("Documents/.trash/old.txt")

        val result = indexer().run(root)

        val names = store.rows.values.map { it.name }.toSet()
        assertEquals(setOf("lease.pdf", "notes.md", "photo.jpg", "setlist.txt"), names)
        assertEquals("text of lease.pdf", store.rows.values.single { it.name == "lease.pdf" }.content)
        assertEquals("", store.rows.values.single { it.name == "photo.jpg" }.content)
        assertEquals(4, result.files)
        assertEquals(3, result.withText)
        assertFalse(read.contains("photo.jpg"))
    }

    @Test
    fun `a second run reads only what changed and drops what is gone`() = runBlocking {
        write("Documents/a.txt")
        write("Documents/b.txt")
        val gone = write("Documents/c.txt")
        indexer().run(root)
        read.clear()

        write("Documents/b.txt", "new", modified = 2_000L)
        gone.delete()
        write("Documents/d.txt")
        val result = indexer().run(root)

        assertEquals(listOf("d.txt", "b.txt"), read)
        assertEquals(2, result.read)
        assertEquals(1, result.removed)
        assertEquals(setOf("a.txt", "b.txt", "d.txt"), store.rows.values.map { it.name }.toSet())
    }

    @Test
    fun `a file that fails to parse keeps its name and counts as a failure`() = runBlocking {
        write("Documents/broken.pdf")
        write("Documents/fine.txt")
        val result = indexer().run(root)
        assertEquals(1, result.failures)
        assertEquals("", store.rows.values.single { it.name == "broken.pdf" }.content)
        assertEquals(2, result.files)
    }

    @Test
    fun `stopping partway keeps what was written and the next run finishes`() = runBlocking {
        (1..5).forEach { write("Documents/f$it.txt") }
        // Stopped once three files are read: those three are written (the last in a part batch).
        val first = indexer().run(root, isStopped = { read.size >= 3 })
        assertTrue(first.stoppedEarly)
        assertEquals(3, store.rows.size)

        val second = indexer().run(root)
        assertFalse(second.stoppedEarly)
        assertEquals(5, store.rows.size)
    }

    @Test
    fun `a stop during the walk changes nothing`() = runBlocking {
        write("Documents/a.txt")
        indexer().run(root)
        write("Documents/b.txt")
        val result = indexer().run(root, isStopped = { true })
        assertTrue(result.stoppedEarly)
        assertEquals(setOf("a.txt"), store.rows.values.map { it.name }.toSet())
    }

    @Test
    fun `an unreadable root changes nothing`() = runBlocking {
        write("Documents/a.txt")
        indexer().run(root)
        val result = indexer().run(File(root, "missing"))
        assertTrue(result.unreadable)
        assertEquals(setOf("a.txt"), store.rows.values.map { it.name }.toSet())
    }

    @Test
    fun `storage that suddenly lists empty keeps the index`() = runBlocking {
        write("Documents/a.txt")
        indexer().run(root)
        File(root, "Documents").deleteRecursively()
        val result = indexer().run(root)
        assertTrue(result.unreadable)
        assertEquals(1, store.rows.size)
    }

    @Test
    fun `progress reaches the total`() = runBlocking {
        (1..5).forEach { write("Documents/f$it.txt") }
        val seen = mutableListOf<Pair<Int, Int>>()
        indexer().run(root, progress = { d, t -> seen += d to t })
        assertEquals(0 to 5, seen.first())
        assertEquals(5 to 5, seen.last())
    }
}
