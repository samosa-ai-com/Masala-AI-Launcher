package com.samosaai.masala.core

import com.samosaai.masala.core.tool.FileMime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileMimeTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `detects common types from their first bytes`() {
        assertEquals("image/png", FileMime.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("image/jpeg", FileMime.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("application/pdf", FileMime.detect("%PDF-1.7".toByteArray()))
        assertEquals("application/zip", FileMime.detect(bytes(0x50, 0x4B, 0x03, 0x04, 0)))
    }

    @Test
    fun `webp needs both the RIFF header and the WEBP tag`() {
        val webp = "RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WEBP".toByteArray()
        assertEquals("image/webp", FileMime.detect(webp))
        val wave = "RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WAVE".toByteArray()
        assertNull(FileMime.detect(wave))
    }

    @Test
    fun `too short or unknown is null`() {
        assertNull(FileMime.detect(bytes(0x89, 0x50)))
        assertNull(FileMime.detect("hello world".toByteArray()))
    }

    @Test
    fun `classifies mime types`() {
        assertTrue(FileMime.isImage("image/png"))
        assertTrue(FileMime.isArchive("application/gzip"))
        assertTrue(FileMime.isDocument("application/pdf"))
        assertFalse(FileMime.isImage("application/pdf"))
    }
}
