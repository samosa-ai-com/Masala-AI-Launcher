package com.samosaai.masala.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Attachment files for the media tools: materializing, path lines, pruning and confinement. */
class ChatAttachmentPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun jpegBase64(): String {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun `materialize writes the file and returns its path`() = runBlocking<Unit> {
        val b64 = jpegBase64()
        val paths = materializeAttachments(tmp.root, "msg-1", listOf(b64))
        assertEquals(1, paths.size)
        val file = java.io.File(paths.single())
        assertTrue(file.isFile)
        assertEquals("0.jpg", file.name)
        assertEquals("msg-1", file.parentFile.name)
        assertEquals(java.util.Base64.getDecoder().decode(b64).toList(), file.readBytes().toList())
    }

    @Test
    fun `materialize skips undecodable entries but keeps the rest`() = runBlocking<Unit> {
        val paths = materializeAttachments(tmp.root, "msg-2", listOf("!!!not-base64!!!", jpegBase64()))
        assertEquals(1, paths.size)
        assertTrue(paths.single().endsWith("msg-2${java.io.File.separator}1.jpg"))
    }

    @Test
    fun `model text carries one path line per image`() {
        assertEquals(
            "find the cat\n[image 1 of 2: /a/0.jpg]\n[image 2 of 2: /a/1.jpg]",
            modelTextWithImagePaths("find the cat", listOf("/a/0.jpg", "/a/1.jpg")),
        )
        assertEquals("[image 1 of 1: /a/0.jpg]", modelTextWithImagePaths("", listOf("/a/0.jpg")))
        assertEquals("plain", modelTextWithImagePaths("plain", emptyList()))
    }

    @Test
    fun `the sweep removes only folders older than fourteen days`() {
        val root = java.io.File(tmp.root, CHAT_ATTACHMENTS_DIR).apply { mkdirs() }
        val old = java.io.File(root, "old").apply {
            mkdirs()
            java.io.File(this, "0.jpg").writeBytes(byteArrayOf(1))
        }
        val fresh = java.io.File(root, "new").apply {
            mkdirs()
            java.io.File(this, "0.jpg").writeBytes(byteArrayOf(2))
        }
        val now = System.currentTimeMillis()
        assertTrue(old.setLastModified(now - ATTACHMENT_RETENTION_MS - 1_000))
        assertTrue(fresh.setLastModified(now))
        java.io.File(tmp.root, "keep.txt").writeText("untouched")

        val removed = sweepOldAttachments(tmp.root, nowMs = now)
        assertEquals(1, removed)
        assertTrue(!old.exists())
        assertTrue(fresh.exists())
        assertTrue(java.io.File(tmp.root, "keep.txt").isFile)
    }

    @Test
    fun `deleting outside the attachments dir is refused`() = runBlocking<Unit> {
        val outside = java.io.File(tmp.root, "outside.txt").apply { writeText("mine") }
        deleteAttachmentFiles(tmp.root, listOf(outside.absolutePath))
        assertTrue(outside.isFile)
    }
}
