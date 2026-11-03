package com.samosaai.masala.core.audio

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavSplitterTest {

    private val rate = 16_000

    private fun wav(seconds: Int): File {
        val dir = java.nio.file.Files.createTempDirectory("split").toFile()
        val file = File(dir, "source.wav")
        val samples = ShortArray(seconds * rate) { i -> (i % 2000 - 1000).toShort() }
        WavPcm.write(file, WavPcm.Audio(samples, rate))
        return file
    }

    @Test fun `a twelve-second file becomes three five-second parts`() {
        val source = wav(12)
        val parts = WavSplitter.split(source, source.parentFile, "chunk", 5_000)
        assertEquals(3, parts.size)
        assertEquals(listOf(0L, 5_000L, 10_000L), parts.map { it.startMs })
        assertEquals(listOf(5_000L, 5_000L, 2_000L), parts.map { it.durationMs })
        assertEquals(listOf(0, 1, 2), parts.map { it.file.name.removeSuffix(".wav").takeLast(1).toInt() })
        parts.forEach { part ->
            val audio = WavPcm.readOrThrow(part.file)
            assertEquals(rate, audio.sampleRate)
            assertEquals(part.durationMs, audio.durationMs)
        }
        source.parentFile.deleteRecursively()
    }

    @Test fun `the parts carry the source samples in order`() {
        val source = wav(3)
        val original = WavPcm.readOrThrow(source)
        val parts = WavSplitter.split(source, source.parentFile, "p", 1_000)
        val joined = parts.flatMap { WavPcm.readOrThrow(it.file).samples.toList() }
        assertEquals(original.samples.size, joined.size)
        assertTrue(joined.indices.all { abs(joined[it] - original.samples[it]) <= 1 })
        source.parentFile.deleteRecursively()
    }

    @Test fun `a file shorter than the window stays one part`() {
        val source = wav(2)
        val parts = WavSplitter.split(source, source.parentFile, "only", 300_000)
        assertEquals(1, parts.size)
        assertEquals(2_000L, parts.single().durationMs)
        source.parentFile.deleteRecursively()
    }

    @Test fun `a non-wav file is refused`() {
        val dir = java.nio.file.Files.createTempDirectory("split").toFile()
        val file = File(dir, "x.wav")
        file.writeText("not audio")
        assertEquals(0, WavSplitter.split(file, dir, "p", 5_000).size)
        dir.deleteRecursively()
    }
}
