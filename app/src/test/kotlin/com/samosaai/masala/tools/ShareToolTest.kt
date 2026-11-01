package com.samosaai.masala.tools

import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.FileAccess
import com.samosaai.masala.core.tool.FileAccessDeniedException
import com.samosaai.masala.core.tool.FileScope
import com.samosaai.masala.core.tool.ToolFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Validation and the success path; the real sheet needs a phone. */
class ShareToolTest {
    private fun tool(dir: File, deny: Boolean = false): ShareTool {
        val access = object : FileAccess {
            override val scope = FileScope.APP_STORAGE
            override fun resolve(path: String): File {
                if (deny) throw FileAccessDeniedException(path, "grant all-files access")
                return dir.resolve(path)
            }
            override fun outputDir(): File = dir
        }
        return ShareTool(ToolFiles { access }) { ActionResult.ok("shared ${it.name}") }
    }

    @Test fun `a blank path fails before touching storage`() {
        assertFalse(tool(File(".")).share("  ").success)
    }

    @Test fun `an existing file reaches the sheet`() {
        val dir = tempDir()
        File(dir, "a.mp3").apply { writeBytes(ByteArray(10)) }
        val result = tool(dir).share("a.mp3")
        assertTrue(result.success, result.message)
        assertEquals("shared a.mp3", result.message)
    }

    @Test fun `a missing file points at discovery`() {
        val result = tool(tempDir()).share("nope.mp3")
        assertFalse(result.success)
        assertTrue("list_files" in result.message || "glob" in result.message)
    }

    @Test fun `directories are refused`() {
        val dir = tempDir()
        File(dir, "sub").mkdir()
        assertFalse(tool(dir).share("sub").success)
    }

    @Test fun `denied access comes back as a permission need`() {
        val result = tool(tempDir(), deny = true).share("x.mp3")
        assertFalse(result.success)
        assertTrue(result.needsPermission == ToolFiles.ALL_FILES_ACCESS)
    }

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("share-test").toFile().also { it.deleteOnExit() }
}
