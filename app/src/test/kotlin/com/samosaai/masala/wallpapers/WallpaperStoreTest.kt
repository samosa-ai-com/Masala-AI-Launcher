package com.samosaai.masala.wallpapers

import com.samosaai.masala.data.WallpaperDao
import com.samosaai.masala.data.WallpaperEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WallpaperStoreTest {
    private class FakeDao : WallpaperDao {
        val rows = mutableListOf<WallpaperEntity>()
        private var seq = 1L
        override suspend fun insert(wallpaper: WallpaperEntity): Long {
            rows += wallpaper.copy(id = seq)
            return seq++
        }
        override suspend fun get(id: Long): WallpaperEntity? = rows.firstOrNull { it.id == id }
        override suspend fun all(): List<WallpaperEntity> = rows.sortedByDescending { it.createdAt }
        override suspend fun search(query: String): List<WallpaperEntity> =
            rows.filter { it.prompt.contains(query, ignoreCase = true) || it.style.contains(query, ignoreCase = true) }
                .sortedByDescending { it.createdAt }
        override suspend fun delete(id: Long) {
            rows.removeAll { it.id == id }
        }
    }

    private fun store(dir: File, dao: FakeDao = FakeDao()) = WallpaperStore(dao, dir) to dao

    @Test
    fun `added wallpapers resolve to their files, newest first`() = runBlocking {
        val dir = Files2.tempDir()
        val (store, _) = store(dir)
        store.add("a.png", "misty forest", "")
        Thread.sleep(2)
        store.add("b.png", "desert dunes", "minimalist")
        val all = store.all()
        assertEquals(listOf("desert dunes", "misty forest"), all.map { it.prompt })
        assertEquals(File(dir, "b.png"), all.first().file)
        assertEquals("minimalist", all.first().style)
    }

    @Test
    fun `blank search returns everything, words filter by prompt or style`() = runBlocking {
        val dir = Files2.tempDir()
        val (store, _) = store(dir)
        store.add("a.png", "misty forest", "")
        store.add("b.png", "desert dunes", "minimalist")
        assertEquals(2, store.search("").size)
        assertEquals(2, store.search("  ").size)
        assertEquals(listOf("misty forest"), store.search("forest").map { it.prompt })
        assertEquals(listOf("desert dunes"), store.search("minimal").map { it.prompt })
        assertEquals(0, store.search("ocean").size)
    }

    @Test
    fun `delete drops the row and its file`() = runBlocking {
        val dir = Files2.tempDir()
        val (store, dao) = store(dir)
        val file = File(dir, "a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val id = store.add("a.png", "misty forest", "")
        assertTrue(file.exists())
        store.delete(id)
        assertTrue(dao.rows.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `prune keeps the newest rows and deletes their files`() = runBlocking {
        val dir = Files2.tempDir()
        val (store, dao) = store(dir)
        repeat(3) { i -> File(dir, "w$i.png").apply { writeBytes(byteArrayOf(1)) }; store.add("w$i.png", "p$i", "") ; kotlinx.coroutines.delay(2) }
        store.prune(2)
        assertEquals(2, dao.rows.size)
        assertEquals(2, dir.listFiles()!!.size)
        assertTrue(dao.rows.none { it.fileName == "w0.png" })
        assertFalse(File(dir, "w0.png").exists())
    }
}

private object Files2 {
    fun tempDir(): File = java.nio.file.Files.createTempDirectory("wallpapers-test").toFile()
        .also { it.deleteOnExit() }
}
