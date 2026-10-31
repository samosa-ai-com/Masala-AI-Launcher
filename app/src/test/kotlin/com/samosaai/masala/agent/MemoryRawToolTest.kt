package com.samosaai.masala.agent

import com.samosaai.masala.core.tool.FileAccess
import com.samosaai.masala.core.tool.FileAccessDeniedException
import com.samosaai.masala.core.tool.FileScope
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.secondbrain.SecondBrainRepository
import com.samosaai.masala.secondbrain.SecondBrainStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** S4: what is copied, what is linked in place, and what is refused. */
class MemoryRawToolTest {
    private val vault = Files.createTempDirectory("sb-raw").toFile()
    // Not under /tmp: that counts as a temporary location, which is never linked.
    private val outside = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "sb-src").toFile().canonicalFile
    private val brain = SecondBrainRepository(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), SecondBrainStore(vault))
        .also { runBlocking { it.ensure() } }

    private class Access(override val scope: FileScope, private val allowed: File) : FileAccess {
        override fun resolve(path: String): File {
            val file = File(path).canonicalFile
            if (!file.path.startsWith(allowed.canonicalPath)) throw FileAccessDeniedException(path, "outside Masala's storage.")
            return file
        }
        override fun outputDir() = allowed
    }

    private fun tool(scope: FileScope, picked: Pair<String, String>? = null) =
        MemoryRawTool.create(brain, { Access(scope, outside) }) { _ ->
            picked?.let { (name, text) -> name to { text.byteInputStream() } }
        }

    private fun run(scope: FileScope, json: String, picked: Pair<String, String>? = null) =
        runBlocking { tool(scope, picked).execute(Json.parseToJsonElement(json) as JsonObject) }

    private fun small() = File(outside, "memo.m4a").apply { writeText("audio") }
    private fun big(dir: File = outside) = File(dir, "film.mp4").apply {
        RandomAccessFile(this, "rw").use { it.setLength(SecondBrainStore.RAW_LINK_THRESHOLD_BYTES + 1) }
    }

    @Test fun smallFilesAreCopied() {
        val result = run(FileScope.APP_STORAGE, """{"source":"${small().path}"}""")
        assertIs<ToolResult.Success>(result)
        assertEquals("audio", File(vault, "raw/memo.m4a").readText())
        assertEquals(listOf("raw/memo.m4a"), brain.remembered.value.map { it.id })
    }

    @Test fun bigFilesAreLinkedOnlyWithAllFilesAccess() {
        val film = big()
        val linked = run(FileScope.SHARED_STORAGE, """{"source":"${film.path}","mode":"link"}""")
        assertIs<ToolResult.Success>(linked)
        assertTrue(linked.output.contains(film.path))
        assertTrue(linked.output.contains("Nothing in the vault tracks it yet"))
        assertTrue(File(vault, "raw").listFiles()!!.isEmpty())
        val copyRefused = run(FileScope.SHARED_STORAGE, """{"source":"${film.path}"}""")
        assertTrue(copyRefused is ToolResult.Failure && copyRefused.message.contains("mode link"))
        val noAccess = run(FileScope.APP_STORAGE, """{"source":"${film.path}","mode":"link"}""")
        assertTrue(noAccess is ToolResult.Failure && noAccess.message.contains("all-files access"))
        // Without all-files access a big file is copied (D4).
        assertIs<ToolResult.Success>(run(FileScope.APP_STORAGE, """{"source":"${film.path}"}"""))
        assertTrue(File(vault, "raw/film.mp4").isFile)
    }

    @Test fun tempFilesAreAlwaysCopied() {
        val cache = File(outside, "cache").apply { mkdirs() }
        val film = big(cache)
        val link = run(FileScope.SHARED_STORAGE, """{"source":"${film.path}","mode":"link"}""")
        assertTrue(link is ToolResult.Failure && link.message.contains("temporary"))
        assertIs<ToolResult.Success>(run(FileScope.SHARED_STORAGE, """{"source":"${film.path}"}"""))
    }

    @Test fun smallFilesAreNotLinked() {
        val link = run(FileScope.SHARED_STORAGE, """{"source":"${small().path}","mode":"link"}""")
        assertTrue(link is ToolResult.Failure && link.message.contains("under 10 MB"))
    }

    @Test fun pickedFilesAreCopiedUnderTheirName() {
        val result = run(FileScope.APP_STORAGE, """{"source":"content://picker/42"}""", "Lease.pdf" to "pdf")
        assertIs<ToolResult.Success>(result)
        assertEquals("pdf", File(vault, "raw/Lease.pdf").readText())
        val link = run(FileScope.SHARED_STORAGE, """{"source":"content://picker/42","mode":"link"}""", "Lease.pdf" to "pdf")
        assertIs<ToolResult.Failure>(link)
        assertIs<ToolResult.Failure>(run(FileScope.APP_STORAGE, """{"source":"content://picker/43"}"""))
    }

    @Test fun unreachableAndMissingPathsAreRefused() {
        assertTrue((run(FileScope.APP_STORAGE, """{"source":"/etc/hosts"}""") as ToolResult.Failure).message.contains("outside"))
        assertIs<ToolResult.Failure>(run(FileScope.APP_STORAGE, """{"source":"${outside.path}/nope.pdf"}"""))
        assertIs<ToolResult.Failure>(run(FileScope.APP_STORAGE, "{}"))
    }
}
