package com.samosaai.masala

import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samosaai.masala.core.files.IndexStatus
import com.samosaai.masala.core.tool.ToolResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Plan Phase 3 exit, Lane B: with All files access granted, the index covers a demo folder and
 * `file_search` finds a phrase inside a PDF. Also: hidden and media folders stay out, a changed
 * file is read again, a deleted one leaves the index. Runs the real job (Index now) and the
 * registered tool; puts the folders and the access mode back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class FileIndexTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private val storage = Environment.getExternalStorageDirectory()
    private val demo = File(storage, "Documents/MasalaIndexDemo")
    private val hidden = File(storage, "Documents/.MasalaIndexHidden")
    private val media = File(storage, "DCIM/MasalaIndexDemo")
    private lateinit var accessBefore: String

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    @Before
    fun grantAccessAndWriteDemo() {
        accessBefore = if ("allow" in shell("appops get ${app.packageName} MANAGE_EXTERNAL_STORAGE")) "allow" else "default"
        shell("appops set ${app.packageName} MANAGE_EXTERNAL_STORAGE allow")
        assertTrue("access not granted", Environment.isExternalStorageManager())
        listOf(demo, hidden, media).forEach { it.deleteRecursively(); it.mkdirs() }

        pdf(File(demo, "lease.pdf"), "The tenant pays a zebraquartz deposit of two months rent.")
        docx(File(demo, "letter.docx"), "Dear landlord, about the lanternfig repairs.")
        File(demo, "notes.txt").writeText("Shopping: pomeloviolet and bread.")
        File(hidden, "secret.txt").writeText("hiddenmarlin should never be indexed")
        File(media, "caption.txt").writeText("dcimotter should never be indexed")
    }

    @After
    fun cleanUp() {
        listOf(demo, hidden, media).forEach { it.deleteRecursively() }
        // One more run so the demo files leave the index too.
        runCatching { indexNowAndWait() }
        shell("appops set ${app.packageName} MANAGE_EXTERNAL_STORAGE $accessBefore")
    }

    @Test
    fun indexFindsAPhraseInsideAPdfAndKeepsUp() {
        indexNowAndWait()

        val lease = search("zebraquartz deposit")
        assertTrue("PDF phrase not found: $lease", "${demo.absolutePath}/lease.pdf" in lease)
        assertTrue("no passage with the match marked: $lease", "[zebraquartz]" in lease)
        assertTrue("DOCX text not found", "letter.docx" in search("lanternfig"))
        assertTrue("found by name", "lease.pdf" in search("lease"))
        assertFalse("hidden folder indexed", "secret.txt" in search("hiddenmarlin"))
        assertFalse("top-level media folder indexed", "caption.txt" in search("dcimotter"))

        // A changed file is read again; a deleted one leaves the index.
        File(demo, "notes.txt").apply {
            writeText("Shopping: kumquatsable and milk.")
            setLastModified(System.currentTimeMillis() + 5_000)
        }
        File(demo, "letter.docx").delete()
        indexNowAndWait()
        assertTrue("changed text not found", "notes.txt" in search("kumquatsable"))
        assertFalse("old text still found", "notes.txt" in search("pomeloviolet"))
        assertFalse("deleted file still found", "letter.docx" in search("lanternfig"))
    }

    private fun search(query: String): String = runBlocking {
        val tool = app.tools["file_search"] ?: error("file_search isn't registered")
        when (val r = tool.execute(JsonObject(mapOf("query" to JsonPrimitive(query))))) {
            is ToolResult.Success -> r.output
            is ToolResult.Failure -> r.message
            is ToolResult.NeedsCapability -> error("needs ${r.capability}")
        }
    }

    private fun indexNowAndWait() {
        val index = app.fileIndex ?: error("no file index in this build")
        val before = index.status.lastRunAt ?: 0L
        index.indexNow()
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val s = index.status
            if (s.state == IndexStatus.State.IDLE && (s.lastRunAt ?: 0L) > before) return
            Thread.sleep(250)
        }
        error("index run didn't finish: ${index.status}")
    }

    private fun pdf(file: File, text: String) {
        PDFBoxResourceLoader.init(app)
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(72f, 700f)
                cs.showText(text)
                cs.endText()
            }
            doc.save(file)
        }
    }

    /** The smallest Word file the parser reads: one paragraph in `word/document.xml`. */
    private fun docx(file: File, text: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>").toByteArray()
            )
            zip.closeEntry()
        }
    }
}
