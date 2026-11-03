package com.samosaai.masala.pro.files

import com.samosaai.masala.core.tool.FileAccess
import com.samosaai.masala.core.tool.FileAccessDeniedException
import com.samosaai.masala.core.tool.FileScope
import com.samosaai.masala.core.tool.ToolFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditToolTest {

    private fun toolIn(dir: File): EditTool {
        val access = object : FileAccess {
            override val scope = FileScope.APP_STORAGE
            override fun resolve(path: String): File = dir.resolve(path)
            override fun outputDir(): File = dir
        }
        return EditTool(ToolFiles { access })
    }

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("edit-test").toFile().also { it.deleteOnExit() }

    @Test fun `replaces one match and leaves the rest alone`() {
        val dir = tempDir()
        val f = File(dir, "a.txt").apply { writeText("one\ntwo\nthree\n") }
        val result = toolIn(dir).edit("a.txt", "two", "TWO")
        assertTrue(result.success, result.message)
        assertTrue("Replaced 1 occurrence" in result.message)
        assertTrue(f.readText() == "one\nTWO\nthree\n")
    }

    @Test fun `blank oldString fails`() {
        val dir = tempDir()
        File(dir, "a.txt").apply { writeText("hi\n") }
        val result = toolIn(dir).edit("a.txt", "  ", "x")
        assertFalse(result.success)
    }

    @Test fun `identical strings fail`() {
        val dir = tempDir()
        File(dir, "a.txt").apply { writeText("hi\n") }
        assertFalse(toolIn(dir).edit("a.txt", "hi", "hi").success)
    }

    @Test fun `missing file hints at discovery`() {
        val result = toolIn(tempDir()).edit("nope.txt", "a", "b")
        assertFalse(result.success)
        assertTrue("list_files" in result.message || "glob" in result.message)
    }

    @Test fun `no match fails`() {
        val dir = tempDir()
        File(dir, "a.txt").apply { writeText("hello\n") }
        val result = toolIn(dir).edit("a.txt", "bye", "x")
        assertFalse(result.success)
        assertTrue("No match" in result.message)
    }

    @Test fun `multiple matches need replaceAll`() {
        val dir = tempDir()
        File(dir, "a.txt").apply { writeText("x=1\nx=1\n") }
        val strict = toolIn(dir).edit("a.txt", "x=1", "x=2")
        assertFalse(strict.success)
        assertTrue("replace_all" in strict.message)
        val all = toolIn(dir).edit("a.txt", "x=1", "x=2", replaceAll = true)
        assertTrue(all.success, all.message)
        assertTrue("2 occurrences" in all.message)
        assertTrue(File(dir, "a.txt").readText() == "x=2\nx=2\n")
    }

    @Test fun `binary files are refused`() {
        val dir = tempDir()
        File(dir, "b.bin").apply { writeBytes(ByteArray(100) { 0 }) }
        assertFalse(toolIn(dir).edit("b.bin", "a", "b").success)
    }

    @Test fun `directories are refused`() {
        val dir = tempDir()
        File(dir, "sub").apply { mkdir() }
        assertFalse(toolIn(dir).edit("sub", "a", "b").success)
    }

    @Test fun `a file just over 1 MB is refused`() {
        val dir = tempDir()
        File(dir, "big.txt").apply { writeBytes(ByteArray((EditTool.MAX_FILE_BYTES + 1).toInt()) { 65 }) }
        val result = toolIn(dir).edit("big.txt", "A", "B")
        assertFalse(result.success)
        assertTrue("1 MB" in result.message)
    }

    @Test fun `malformed UTF-8 is refused, not rewritten`() {
        val dir = tempDir()
        val before = byteArrayOf(0x48.toByte(), 0xC3.toByte(), 0x28.toByte(), 0x0A.toByte())
        File(dir, "latin.txt").apply { writeBytes(before) }
        val result = toolIn(dir).edit("latin.txt", "H", "J")
        assertFalse(result.success)
        assertTrue("UTF-8" in result.message)
        assertTrue(File(dir, "latin.txt").readBytes().contentEquals(before))
    }

    @Test fun `CRLF endings survive and LF spans match`() {
        val dir = tempDir()
        File(dir, "dos.txt").apply { writeBytes("one\r\ntwo\r\nthree\r\n".toByteArray()) }
        val result = toolIn(dir).edit("dos.txt", "one\ntwo", "ONE\nTWO")
        assertTrue(result.success, result.message)
        assertTrue(File(dir, "dos.txt").readBytes().contentEquals("ONE\r\nTWO\r\nthree\r\n".toByteArray()))
    }

    @Test fun `the snippet shows the edited region, not an earlier duplicate`() {
        val dir = tempDir()
        val lines = (1..12).joinToString("\n") { if (it == 2) "TARGET" else if (it == 10) "fixme" else "line$it" } + "\n"
        File(dir, "a.txt").apply { writeText(lines) }
        val result = toolIn(dir).edit("a.txt", "fixme", "TARGET")
        assertTrue(result.success, result.message)
        assertTrue("10: TARGET" in result.message)
        assertFalse("2: TARGET" in result.message)
    }

    @Test fun `denied access comes back as a permission need`() {
        val access = object : FileAccess {
            override val scope = FileScope.APP_STORAGE
            override fun resolve(path: String): File = throw FileAccessDeniedException(path, "grant all-files access")
            override fun outputDir(): File = tempDir()
        }
        val result = EditTool(ToolFiles { access }).edit("x.txt", "a", "b")
        assertFalse(result.success)
        assertTrue(result.needsPermission == ToolFiles.ALL_FILES_ACCESS)
    }
}
