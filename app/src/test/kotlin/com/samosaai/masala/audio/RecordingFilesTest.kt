package com.samosaai.masala.audio

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingFilesTest {
    @Test
    fun `prune keeps newest chunks and drops the rest`() {
        val dir = createTempDirectory("rec-test").toFile()
        try {
            (1..5).forEach { File(dir, RecordingFiles.chunkName(it.toLong())).writeText("x") }
            File(dir, "notes.txt").writeText("keep me")
            val dropped = RecordingFiles.prune(dir, keep = 3)
            assertEquals(listOf("rec-1.m4a", "rec-2.m4a"), dropped)
            assertTrue(File(dir, "rec-5.m4a").exists())
            assertTrue(File(dir, "notes.txt").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Timestamps of different digit counts must order numerically, not as strings. */
    @Test
    fun `prune orders by timestamp digits, not string order`() {
        val dir = createTempDirectory("rec-test").toFile()
        try {
            val stamps = listOf(2L, 10L, 99L, 100L, 1000L)
            stamps.forEach { File(dir, RecordingFiles.chunkName(it)).writeText("x") }
            val dropped = RecordingFiles.prune(dir, keep = 2)
            assertEquals(listOf("rec-2.m4a", "rec-10.m4a", "rec-99.m4a"), dropped)
            assertTrue(File(dir, "rec-1000.m4a").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `prune with room for all drops nothing`() {
        val dir = createTempDirectory("rec-test").toFile()
        try {
            (1..2).forEach { File(dir, RecordingFiles.chunkName(it.toLong())).writeText("x") }
            assertEquals(emptyList<String>(), RecordingFiles.prune(dir, keep = 48))
        } finally {
            dir.deleteRecursively()
        }
    }
}
