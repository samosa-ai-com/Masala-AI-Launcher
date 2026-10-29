package com.samosaai.masala.pro.files

import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.pro.data.FileHit
import kotlinx.coroutines.runBlocking
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSearchToolTest {
    private val asked = mutableListOf<Triple<String, String, Int>>()
    private var found = listOf(
        FileHit("/storage/emulated/0/Documents/lease.pdf", "lease.pdf", 1_793_232_000_000L, "…the [deposit] is two months' rent…"),
        FileHit("/storage/emulated/0/Download/deposit.txt", "deposit.txt", 1_793_000_000_000L, "deposit.txt"),
    )

    private fun tool(files: Int = 10, access: Boolean = true, indexing: Boolean = false) = FileSearchTool(
        hits = { m, f, l -> asked += Triple(m, f, l); found },
        indexedFiles = { files },
        hasAccess = { access },
        storage = "/storage/emulated/0",
        indexing = { indexing },
        zone = ZoneOffset.UTC,
    )

    @Test fun `matches come back with path, date and passage`() = runBlocking {
        val result = tool().search("rental deposit", null, null)
        assertTrue(result.success)
        assertEquals(
            "1. /storage/emulated/0/Documents/lease.pdf (modified 2026-10-29)\n   …the [deposit] is two months' rent…\n" +
                "2. /storage/emulated/0/Download/deposit.txt (modified 2026-10-26)",
            result.message,
        )
        assertEquals(Triple("\"rental\" \"deposit*\"", "/", 10), asked.single())
    }

    @Test fun `folders the model names become path prefixes`() {
        val storage = "/storage/emulated/0"
        assertEquals("/", FileSearchTool.folderPrefix(null, storage))
        assertEquals("/storage/emulated/0/Download/", FileSearchTool.folderPrefix("Download", storage))
        assertEquals("/storage/emulated/0/Documents/", FileSearchTool.folderPrefix("/sdcard/Documents/", storage))
        assertEquals("/storage/emulated/0/Documents/", FileSearchTool.folderPrefix("/storage/emulated/0/Documents", storage))
        assertEquals("/storage/emulated/10/Download/", FileSearchTool.folderPrefix("/sdcard/Download", "/storage/emulated/10"))
    }

    @Test fun `LIKE wildcards in a folder name match only themselves`() = runBlocking<Unit> {
        assertEquals("/storage/emulated/0/my\\_files\\%/", FileSearchTool.likePrefix("/storage/emulated/0/my_files%/"))
        assertEquals("a\\\\b", FileSearchTool.likePrefix("a\\b"))
        tool().search("x", "Documents/my_files", null)
        assertEquals("/storage/emulated/0/Documents/my\\_files/", asked.single().second)
    }

    @Test fun `the limit is kept in range`() = runBlocking<Unit> {
        tool().search("x", null, 500)
        tool().search("x", null, 0)
        assertEquals(listOf(30, 1), asked.map { it.third })
    }

    @Test fun `without access it asks for all-files access`() = runBlocking {
        val result = tool(access = false).search("lease", null, null)
        assertEquals(ToolFiles.ALL_FILES_ACCESS, result.needsPermission)
        assertTrue(asked.isEmpty())
    }

    @Test fun `an empty index or no words says what to do instead`() = runBlocking {
        val empty = tool(files = 0).search("lease", null, null)
        assertFalse(empty.success)
        assertTrue("glob" in empty.message && "Index now" in empty.message)
        assertFalse(tool().search("?!", null, null).success)
        val first = tool(files = 0, indexing = true).search("lease", null, null)
        assertFalse(first.success)
        assertTrue("being built" in first.message && "Index now" !in first.message)
    }

    @Test fun `no match is an answer, not an error`() = runBlocking {
        found = emptyList()
        val result = tool().search("unicorn", null, null)
        assertTrue(result.success)
        assertTrue("grep" in result.message)
    }
}
