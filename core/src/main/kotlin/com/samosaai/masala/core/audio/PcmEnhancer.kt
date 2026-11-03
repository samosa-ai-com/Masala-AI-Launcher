package com.samosaai.masala.core.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Makes a quiet room recording usable by the transcriber before it is uploaded. Measurements on the
 * real endpoint showed quiet audio still transcribes, but level is what fails first on a phone that
 * sat on a table three metres away, so the chain is:
 *
 * 1. DC offset removal (cheap, avoids a constant bias eating headroom);
 * 2. a 2nd-order high-pass at [HIGH_PASS_HZ] (HVAC rumble, table bumps, handling noise);
 * 3. a frame gate: frames under the noise floor + [GATE_ABOVE_FLOOR_DB] are attenuated, not muted,
 *    so pauses stay natural and the model does not hear chopped silence;
 * 4. **median level normalization** to [TARGET_MEDIAN_DBFS]: a median, not a peak, so one cough or a
 *    door slam does not decide the gain of the whole window;
 * 5. a whole-signal limiter at [LIMIT_DBFS] so the gain cannot clip.
 *
 * This is level/rumble work, not model-based noise removal, and the UI says so. Pure Kotlin, no
 * Android, so `:core` tests it in seconds.
 */
object PcmEnhancer {

    /** Median active-frame level the upload aims for. Speech is usually mastered near −20 dBFS. */
    const val TARGET_MEDIAN_DBFS = -20.0

    /** A very quiet phone recording needs up to this much lift; past it the noise is lifted too. */
    const val MAX_GAIN_DB = 24.0

    /** Frames this far above the estimated noise floor count as speech. */
    const val GATE_ABOVE_FLOOR_DB = 6.0

    /** What a below-floor frame is multiplied by (about −9 dB), not zero. */
    const val GATE_GAIN = 0.35

    const val HIGH_PASS_HZ = 85.0
    const val LIMIT_DBFS = -1.0
    const val FRAME_MS = 20

    /** Anything under this is digital silence, not a floor estimate. */
    private const val SILENCE_DBFS = -70.0

    /** The enhanced copy. The input array is not modified. */
    fun enhance(samples: ShortArray, sampleRate: Int): ShortArray {
        if (samples.isEmpty() || sampleRate <= 0) return ShortArray(0)
        val work = DoubleArray(samples.size) { samples[it].toDouble() }
        removeDc(work)
        highPass(work, sampleRate)

        val frames = frameLevels(work, sampleRate)
        if (frames.isEmpty()) return ShortArray(samples.size) { samples[it] }
        val audible = frames.filter { it > SILENCE_DBFS }
        // All silence: nothing to normalize, and lifting the noise would only invent speech.
        if (audible.isEmpty()) return ShortArray(samples.size)

        // The gate needs a noise floor that is actually quieter than the speech. Continuous speech
        // (or a test tone) has no pauses, so its 10th percentile *is* speech; gating there would
        // attenuate every frame, which is worse than no gate at all.
        val floor = percentile(audible, 0.10)
        val overall = median(audible)
        val gateEnabled = overall - floor >= GATE_ABOVE_FLOOR_DB + 4.0
        val active = if (gateEnabled) audible.filter { it >= floor + GATE_ABOVE_FLOOR_DB } else audible
        val median = median(active)
        val gainDb = (TARGET_MEDIAN_DBFS - median).coerceIn(0.0, MAX_GAIN_DB)
        val gain = 10.0.pow(gainDb / 20.0)

        // Per-frame multiplier (gate), smoothed so the gate does not click.
        val perFrame = DoubleArray(frames.size) { i ->
            if (!gateEnabled || frames[i] >= floor + GATE_ABOVE_FLOOR_DB) 1.0 else GATE_GAIN
        }
        for (i in 1 until perFrame.size) {
            val target = perFrame[i]
            val previous = perFrame[i - 1]
            // Fast to open, slow to close: a word starting after a pause must not be swallowed.
            val step = if (target > previous) 0.5 else 0.15
            perFrame[i] = previous + (target - previous) * step
        }

        val frameSize = frameSize(sampleRate)
        val out = ShortArray(work.size)
        // A knee instead of a whole-signal scale: one cough must not turn the rest of the window
        // down. Below the knee nothing changes; above it, tanh approaches the ceiling smoothly.
        val limit = 32767.0 * 10.0.pow(LIMIT_DBFS / 20.0)
        val knee = limit * 0.68
        val room = (limit - knee).coerceAtLeast(1.0)
        for (i in work.indices) {
            val frame = i / frameSize
            val next = min(frame + 1, perFrame.size - 1)
            val at = (i % frameSize).toDouble() / frameSize
            val gate = perFrame[frame] + (perFrame[next] - perFrame[frame]) * at
            var value = work[i] * gain * gate
            val magnitude = abs(value)
            if (magnitude > knee) {
                val squashed = knee + room * kotlin.math.tanh((magnitude - knee) / room)
                value = if (value < 0) -squashed else squashed
            }
            out[i] = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** Mean level of a window in dBFS; `SILENCE_DBFS` or lower means nothing audible. */
    fun levelDb(samples: ShortArray, from: Int = 0, to: Int = samples.size): Double {
        if (to <= from) return SILENCE_DBFS
        var sum = 0.0
        for (i in from until to) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / (to - from))
        return if (rms <= 0.0) SILENCE_DBFS else 20.0 * log10(rms)
    }

    private fun frameSize(sampleRate: Int): Int = max(1, sampleRate * FRAME_MS / 1000)

    private fun frameLevels(samples: DoubleArray, sampleRate: Int): List<Double> {
        val size = frameSize(sampleRate)
        val frames = ceil(samples.size.toDouble() / size).toInt()
        return (0 until frames).map { f ->
            val from = f * size
            val to = min(from + size, samples.size)
            var sum = 0.0
            for (i in from until to) sum += samples[i] * samples[i]
            val rms = sqrt(sum / (to - from).coerceAtLeast(1)) / 32768.0
            if (rms <= 0.0) SILENCE_DBFS else 20.0 * log10(rms)
        }
    }

    private fun removeDc(samples: DoubleArray) {
        var sum = 0.0
        for (v in samples) sum += v
        val mean = sum / samples.size
        for (i in samples.indices) samples[i] -= mean
    }

    private fun highPass(samples: DoubleArray, sampleRate: Int) {
        val w0 = 2.0 * Math.PI * HIGH_PASS_HZ / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * 0.707)
        val a0 = 1.0 + alpha
        val b0 = (1.0 + cosW) / 2.0 / a0
        val b1 = -(1.0 + cosW) / a0
        val b2 = b0
        val a1 = -2.0 * cosW / a0
        val a2 = (1.0 - alpha) / a0
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in samples.indices) {
            val x = samples[i]
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            samples[i] = y
        }
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun median(values: List<Double>): Double = percentile(values, 0.5)
}
