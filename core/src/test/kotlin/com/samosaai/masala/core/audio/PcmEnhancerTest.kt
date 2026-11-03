package com.samosaai.masala.core.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class PcmEnhancerTest {

    private val rate = 16_000

    private fun tone(ms: Int, amplitude: Double, hz: Double = 220.0, offset: Int = 0): ShortArray =
        ShortArray(ms * rate / 1000) { i ->
            (sin(2 * PI * hz * (i + offset) / rate) * amplitude).toInt().toShort()
        }

    private fun medianLevel(samples: ShortArray): Double {
        val size = rate * 20 / 1000
        val frames = (0 until samples.size / size).map { f -> PcmEnhancer.levelDb(samples, f * size, (f + 1) * size) }
        return frames.sorted()[frames.size / 2]
    }

    @Test fun `very quiet speech is lifted to the target median`() {
        val quiet = tone(3_000, 300.0)
        assertTrue(PcmEnhancer.levelDb(quiet) < -38.0)
        val enhanced = PcmEnhancer.enhance(quiet, rate)
        val level = medianLevel(enhanced)
        assertTrue(abs(level - PcmEnhancer.TARGET_MEDIAN_DBFS) <= 3.0, "median $level should be near the target")
    }

    @Test fun `a loud recording is not made louder and cannot clip`() {
        val loud = tone(2_000, 30_000.0)
        val enhanced = PcmEnhancer.enhance(loud, rate)
        val peak = enhanced.maxOf { abs(it.toInt()) }
        assertTrue(peak <= 32767 * 0.9 + 2, "peak $peak must stay under the limiter")
        assertTrue(medianLevel(enhanced) <= 0.0, "loud audio must not be lifted")
    }

    @Test fun `silence stays silent`() {
        val silent = ShortArray(rate * 2)
        val enhanced = PcmEnhancer.enhance(silent, rate)
        assertEquals(0, enhanced.count { it.toInt() != 0 })
    }

    @Test fun `a single cough does not set the gain`() {
        val quiet = tone(4_000, 260.0)
        val cough = tone(300, 32_000.0, hz = 900.0)
        val mixed = ShortArray(quiet.size + cough.size)
        quiet.copyInto(mixed, 0)
        cough.copyInto(mixed, quiet.size)
        val enhanced = PcmEnhancer.enhance(mixed, rate)
        // The speech at the start is lifted; the cough is limited rather than distorting the take.
        val speechLevel = medianLevel(enhanced.copyOfRange(0, quiet.size))
        assertTrue(abs(speechLevel - PcmEnhancer.TARGET_MEDIAN_DBFS) <= 4.0, "speech median $speechLevel should be near the target")
        assertTrue(enhanced.maxOf { abs(it.toInt()) } <= 32767 * 0.9 + 2)
    }

    @Test fun `a DC offset is removed`() {
        val biased = tone(1_000, 200.0)
        for (i in biased.indices) biased[i] = (biased[i] + 4_000).toShort()
        val enhanced = PcmEnhancer.enhance(biased, rate)
        val mean = enhanced.sumOf { it.toInt() }.toDouble() / enhanced.size
        assertTrue(abs(mean) < 200, "mean offset $mean should be near zero")
    }

    @Test fun `an empty array is safe`() {
        assertEquals(0, PcmEnhancer.enhance(ShortArray(0), rate).size)
    }

    @Test fun `WAV round trips and joins with an overlap`() {
        val dir = java.nio.file.Files.createTempDirectory("wav").toFile()
        val a = File(dir, "a.wav")
        val b = File(dir, "b.wav")
        WavPcm.write(a, WavPcm.Audio(tone(1_000, 1_000.0), rate))
        WavPcm.write(b, WavPcm.Audio(tone(1_000, 500.0), rate))
        val readA = WavPcm.readOrThrow(a)
        assertEquals(rate, readA.sampleRate)
        assertEquals(16_000, readA.samples.size)
        assertEquals(1_000L, readA.durationMs)
        val joined = WavPcm.withOverlap(readA, readB(b), tailMs = 250)
        assertEquals(1_000 * 16 + 250 * 16, joined.samples.size)
        dir.deleteRecursively()
    }

    private fun readB(file: File) = WavPcm.readOrThrow(file)
}
