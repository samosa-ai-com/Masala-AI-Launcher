package com.samosaai.masala.audio

/**
 * A noise-floor-adaptive voice-activity detector used by the hands-free
 * wake-word call flow.
 *
 * It is fed one RMS (dBFS) value per audio window. The noise floor is the
 * minimum over a rolling window of recent RMS values: the minimum (unlike a
 * percentile) needs every window loud to be dragged up, so speech starting the
 * moment the mic opens can't contaminate it the way a percentile can — only a
 * pause-free 2.4 s shout would. The rolling window only records windows heard
 * before speech is accepted, so sustained speech can never drag the floor up
 * mid-utterance. Speech is any window above `noiseFloor + margin` (never below
 * an absolute floor). A window must stay above the threshold for
 * [ACCEPT_STREAK] consecutive windows before it counts as speech, so a
 * one-window transient (door slam, cough) is ignored.
 *
 * Acceptance doesn't wait for the floor window to prime: a short command
 * ("open YouTube", ~1.2 s) spoken at once would be over before 12 windows
 * elapse. Early on, varying levels mark speech — steady ambient, however loud,
 * never accepts until the floor has learned it.
 *
 * Once speech has been accepted, the detector reports [Decision.STOP] after
 * [silenceTimeoutMs] of trailing silence — the clock only starts after genuine
 * speech, so a user who pauses before beginning to speak is not cut off. If
 * the user never spoke, it reports [Decision.STOP_NO_SPEECH] after
 * [noSpeechTimeoutMs].
 */
internal class SilenceDetector(
    private val silenceTimeoutMs: Long,
    private val noSpeechTimeoutMs: Long = NO_SPEECH_TIMEOUT_DEFAULT_MS,
    private val speechMarginDb: Float = SPEECH_MARGIN_DB,
    private val absoluteFloorDb: Float = ABSOLUTE_FLOOR_DB
) {
    var speechDetected: Boolean = false
        private set

    private val recentRms = FloatArray(FLOOR_WINDOW)
    private var recentCount = 0
    private var recentStart = 0
    private var aboveThresholdStreak = 0
    private var startMs = -1L
    private var lastSpeechAtMs = -1L

    /** The RMS level (dBFS) a window must exceed to count as speech. */
    val thresholdDb: Float
        get() = maxOf(absoluteFloorDb, noiseFloorEstimate + speechMarginDb)

    /** The current noise-floor estimate, for diagnostics (the test card shows it). */
    val floorDb: Float
        get() = noiseFloorEstimate

    fun update(windowRmsDb: Float, nowMs: Long): Decision {
        if (startMs < 0) startMs = nowMs
        // Only pre-speech windows inform the floor, so the estimate keeps
        // reflecting ambient even while the user talks over it.
        if (!speechDetected) {
            recentRms[recentStart] = windowRmsDb
            recentStart = (recentStart + 1) % FLOOR_WINDOW
            if (recentCount < FLOOR_WINDOW) recentCount++
        }

        if (windowRmsDb > thresholdDb) {
            aboveThresholdStreak++
            // Either the floor has learned the room (primed), or the signal is
            // visibly varying (speech over ambient): steady sound alone — even
            // loud — never accepts before the floor knows it.
            if (!speechDetected && aboveThresholdStreak >= ACCEPT_STREAK &&
                (recentCount >= FLOOR_WINDOW || levelRangeDb >= VARIATION_DB)
            ) {
                speechDetected = true
            }
            lastSpeechAtMs = nowMs
        } else {
            aboveThresholdStreak = 0
        }
        if (speechDetected && nowMs - lastSpeechAtMs >= silenceTimeoutMs) {
            return Decision.STOP
        }
        if (!speechDetected && nowMs - startMs >= noSpeechTimeoutMs) {
            return Decision.STOP_NO_SPEECH
        }
        return Decision.CONTINUE
    }

    /**
     * Spread of the levels heard so far, in dBFS. Speech jumps 15–30 dB over
     * ambient within a few windows; steady room noise moves 1–3 dB. Only the
     * filled prefix counts — the rest of the ring is zero-initialized, not audio.
     */
    private val levelRangeDb: Float
        get() {
            val n = minOf(recentCount, FLOOR_WINDOW)
            if (n < 2) return 0f
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until n) {
                lo = minOf(lo, recentRms[i])
                hi = maxOf(hi, recentRms[i])
            }
            return hi - lo
        }

    /**
     * Minimum of the recent RMS windows, in dBFS. The minimum (unlike a low
     * percentile) needs every window loud to rise, so a few speech windows
     * during priming can't drag it up; one quiet dip is enough to hold it down.
     * Letting it rise still tracks louder rooms without treating the ambient
     * level as speech.
     */
    private val noiseFloorEstimate: Float
        get() {
            if (recentCount < FLOOR_WINDOW) return INITIAL_NOISE_FLOOR_DB
            var floor = Float.MAX_VALUE
            for (i in 0 until FLOOR_WINDOW) floor = minOf(floor, recentRms[i])
            return maxOf(floor, absoluteFloorDb)
        }

    enum class Decision { CONTINUE, STOP, STOP_NO_SPEECH }

    companion object {
        private const val NO_SPEECH_TIMEOUT_DEFAULT_MS = 15_000L
        private const val SPEECH_MARGIN_DB = 10f
        /** Floor of [rmsDb]; a stream of zeros reads exactly this. */
        private const val ABSOLUTE_FLOOR_DB = -50f

        private const val INITIAL_NOISE_FLOOR_DB = -60f

        /** Consecutive above-threshold windows required before accepting speech. */
        private const val ACCEPT_STREAK = 3

        /** Number of recent RMS windows the floor minimum is computed over. */
        private const val FLOOR_WINDOW = 12

        /**
         * Level spread that marks speech before the floor is primed. Steady rooms
         * move 1–3 dB; speech onsets jump 15–30 dB. 6 dB sits well between.
         */
        private const val VARIATION_DB = 6f

        /** RMS of [count] 16-bit PCM samples in dBFS (clamped to [ABSOLUTE_FLOOR_DB]). */
        fun rmsDb(samples: ShortArray, count: Int): Float {
            if (count <= 0) return ABSOLUTE_FLOOR_DB
            var sum = 0L
            for (i in 0 until count) {
                val sample = samples[i].toInt()
                sum += sample.toLong() * sample
            }
            val rms = kotlin.math.sqrt(sum.toDouble() / count)
            if (rms <= 0.0) return ABSOLUTE_FLOOR_DB
            val db = 20.0 * kotlin.math.log10(rms / 32768.0)
            return maxOf(db, ABSOLUTE_FLOOR_DB.toDouble()).toFloat()
        }
    }
}
