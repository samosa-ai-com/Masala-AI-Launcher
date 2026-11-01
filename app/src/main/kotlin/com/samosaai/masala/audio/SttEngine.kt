package com.samosaai.masala.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.samosaai.masala.R
import com.samosaai.masala.i18n.Language
import com.samosaai.masala.util.MasalaLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/** Result of a single hands-free listening turn ([SttEngine.listenOnceAndroid]). */
sealed class SttOutcome {
    data class Text(val text: String) : SttOutcome()
    data class Error(val code: Int) : SttOutcome() {
        /** True for silence/no-speech errors that a continuous loop should just retry. */
        val isBenign: Boolean
            get() = code == SpeechRecognizer.ERROR_NO_MATCH ||
                code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }
}

/**
 * From the private prototype's `SttEngine`: package, `R` and log calls only; settings come from the caller (see [SpeechConfig]).
 * Speech-to-Text engine supporting two providers.
 * A tap starts an open-ended take; Send (or cancel, failure, or the safety cap)
 * ends it, and the engine reports a take that ended by itself at once.
 *
 * - API provider: records 16 kHz PCM via AudioRecord, transcribes the WAV via API
 * - Android provider: uses SpeechRecognizer (live recognition)
 */
@Suppress("TooManyFunctions")
class SttEngine(
    private val context: Context,
    apiBaseUrl: String = "",
    apiKey: String = "",
    private val onUnauthorized: (() -> Unit)? = null
) : PttEngine {
    // API STT state
    private var audioApi: AudioApi? = if (apiBaseUrl.isNotBlank()) {
        AudioApi(apiBaseUrl, apiKey, onUnauthorized = onUnauthorized)
    } else {
        null
    }

    /** Set by [cancelListening] to abort an in-flight VAD (hands-free) recording. */
    @Volatile
    private var vadStopRequested = false

    /** Set by [finishUtterance] (tap Stop): transcribe what's there even if the VAD never fired. */
    @Volatile
    private var vadUserStopped = false

    /**
     * Why the last [recordPcmWithVad] returned null without the user staying quiet (recorder
     * wouldn't start, reads failed): reported instead of "no speech". Cleared on read.
     */
    private var vadRecordError: Exception? = null

    // Audio muting state
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var isMutedForRestart = false

    // Android STT state
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentRecognizer: SpeechRecognizer? = null
    private var listenGate: CompletableDeferred<String>? = null
    private var onceGate: CompletableDeferred<SttOutcome>? = null

    // Continuous push-to-talk state
    private var isContinuousListening = false
    private val continuousTranscript = StringBuilder()

    /** Latest partial result from the current recognizer segment (not yet finalized). */
    private var currentPartialResult = ""

    private val _level = kotlinx.coroutines.flow.MutableStateFlow(0f)

    /** The mic level while listening, 0..1 (UI refresh U4: Sam's rings and the level bars follow it). */
    val level: kotlinx.coroutines.flow.StateFlow<Float> = _level

    /** Fires once when a take ends by itself; see [PttEngine.TakeAutoEnd]. Main thread. */
    @Volatile
    private var takeAutoEndListener: ((PttEngine.TakeAutoEnd) -> Unit)? = null

    override fun setTakeAutoEndListener(listener: ((PttEngine.TakeAutoEnd) -> Unit)?) {
        takeAutoEndListener = listener
    }

    /**
     * Reports a take that ended by itself, once: the listener is cleared first so a
     * later Send or cancel can't re-fire it. The forwarders clear their own state on
     * stop/cancel too, so a post already in flight is dropped there as well.
     */
    private fun postTakeAutoEnd(end: PttEngine.TakeAutoEnd) {
        val listener = takeAutoEndListener ?: return
        takeAutoEndListener = null
        mainHandler.post { listener(end) }
    }

    private val _heard = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * Bumped every time new words arrive from the recognizer. Its only job is letting a
     * caller tell "still speaking" from "gone quiet" without guessing at partial text,
     * which the recognizer keeps around after a pause.
     */
    val heard: kotlinx.coroutines.flow.StateFlow<Long> = _heard

    /** What the voice-activity detector currently sees; the settings test card shows it. */
    data class VadSnapshot(val floorDb: Float, val thresholdDb: Float, val peakDb: Float, val speechHeard: Boolean)

    private val _vadState = kotlinx.coroutines.flow.MutableStateFlow<VadSnapshot?>(null)
    val vadState: kotlinx.coroutines.flow.StateFlow<VadSnapshot?> = _vadState

    /** A new listening session starts from silence. */
    fun resetLive() {
        _level.value = 0f
    }

    /** Language for the current/next Android recognizer session. */
    private var currentLanguage = Language.ENGLISH

    // Android Built-in recognizer choice; see [configureAndroid].
    private var androidService = ""
    private var androidOnDeviceOnly = false

    /**
     * Why the last Android listen failed in a way the user has to fix (no
     * on-device recognition, the language not available), or null. Callers
     * show it instead of a generic failure or "no speech".
     */
    @Volatile
    var androidFailure: String? = null
        private set

    /**
     * Push-to-talk keeps its recognizer between sessions; set when
     * [configureAndroid] changes the choice so the next session makes a new one.
     */
    @Volatile
    private var recognizerStale = false

    /** The language tag this push-to-talk session asks for, once known; see [listenWithInstalledTag]. */
    private var sessionTag: String? = null

    /** Told at once when this push-to-talk session fails for good; see [startAndroidListening]. */
    private var sessionFailure: ((String) -> Unit)? = null

    /** Choose Android Built-in's recognizer: [service] (blank = the phone's), or the on-device one. */
    fun configureAndroid(service: String, onDeviceOnly: Boolean) {
        if (service == androidService && onDeviceOnly == androidOnDeviceOnly) return
        androidService = service
        androidOnDeviceOnly = onDeviceOnly
        recognizerStale = true
    }

    /** The recognizer [configureAndroid] chose, or null when on-device recognition isn't there. Main thread only. */
    private fun createAndroidRecognizer(): SpeechRecognizer? =
        AndroidSpeechInfo.createRecognizer(context, androidService, androidOnDeviceOnly)

    /**
     * Start [recognizer] on the variant of [language] it has installed, so a
     * phone whose offline English is Indian gets `en-IN` rather than the
     * `en-US` it can't recognise (#122). Asks once per push-to-talk session;
     * [start] runs on the main thread.
     */
    private fun listenWithInstalledTag(recognizer: SpeechRecognizer, language: Language, start: (String) -> Unit) {
        sessionTag?.let { return start(it) }
        AndroidSpeechInfo.installedTag(context, recognizer, language) { tag ->
            val chosen = tag ?: language.bcp47
            sessionTag = chosen
            start(chosen)
        }
    }

    /**
     * End push-to-talk on a failure no retry will fix and say why now, not when
     * the user next taps Stop: the mic would look like it was still listening.
     * Main thread only.
     */
    private fun failSession(message: String) {
        isContinuousListening = false
        try {
            currentRecognizer?.destroy()
        } catch (_: Exception) {}
        currentRecognizer = null
        unmuteBeep()
        listenGate?.complete(buildFinalTranscript())
        val notify = sessionFailure
        if (notify != null) {
            androidFailure = null
            notify(message)
        } else {
            androidFailure = message
        }
    }

    /** The models available from the API (empty if provider is Android). */
    var apiSttModels: List<AudioModel> = emptyList()
        private set

    /** Discover STT models from the API. Call after construction. */
    suspend fun refreshApiModels(): List<AudioModel> = withContext(Dispatchers.IO) {
        val api = audioApi ?: return@withContext emptyList<AudioModel>()
        val models = api.listAudioModels()
        apiSttModels = models.filter { it.category == ModelCategory.STT }
        apiSttModels
    }

    /** Whether any on-device speech recognizer exists (a stub service counts as present but fails later). */
    fun isLocalRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Set API config at runtime. */
    fun configureApi(baseUrl: String, apiKey: String) {
        audioApi = if (baseUrl.isNotBlank()) {
            AudioApi(baseUrl, apiKey, onUnauthorized = onUnauthorized)
        } else {
            null
        }
    }

    // ---- API STT (tap-to-send PCM + transcription) ----
    //
    // A tap starts an open-ended AudioRecord capture (16 kHz mono PCM-16, MIC
    // source), the same capture the test card uses. The take ends on Send, on
    // cancel, or by itself: a dead capture fails fast, a 90 s take hits the
    // safety cap. Both self-ends report through [takeAutoEndListener] at once,
    // so the UI never sits on "Listening…" for a take that is already over.

    private var holdThread: Thread? = null
    private var holdRecorder: AudioRecord? = null

    /** Take generation: a stale thread (join timed out) must not touch the next take. */
    @Volatile
    private var holdGen = 0

    /** Set by [stopRecording]/[cancelListening] to end the hold capture thread. */
    @Volatile
    private var holdStopRequested = false

    /**
     * Why the last [stopRecording] returned null without audio to send (capture
     * wouldn't start, reads failed, the stream was all zeros). Read once, like
     * [vadRecordError] on the hands-free path.
     */
    private var holdError: Exception? = null

    private var holdPcm = ByteArrayOutputStream()

    /** Any non-zero sample at all, like [recordPcmWithVad]'s guard. */
    private var holdSawSignal = false

    /** Start an open-ended capture; the take ends on Send, cancel, failure or cap. */
    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        stopRecordingThread()
        val sampleRate = 16_000
        val windowShorts = HOLD_WINDOW_SHORTS
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // MIC, not VOICE_RECOGNITION: the recognition source applies the vendor's
        // capture processing and on some devices reads back pure zeros.
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, windowShorts * 2)
            )
        } catch (_: Exception) {
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            holdError = Exception("Recording failed to start")
            return false
        }
        holdStopRequested = false
        holdError = null
        holdPcm = ByteArrayOutputStream()
        holdSawSignal = false
        takeAutoEndListener = null
        resetLive()
        _vadState.value = null
        holdRecorder = recorder
        val gen = ++holdGen
        return try {
            recorder.startRecording()
            holdThread = Thread {
                val shorts = ShortArray(windowShorts)
                val bytes = ByteArray(windowShorts * 2)
                var zeroRunMs = 0L
                val startMs = System.currentTimeMillis()
                /**
                 * Ends the take from inside the capture thread: stops the mic indicator
                 * with the take (not on the next Send or tap) and reports it. A later
                 * stop releasing the same recorder again is caught and ignored.
                 */
                fun endTakeAlone(end: PttEngine.TakeAutoEnd) {
                    holdStopRequested = true
                    runCatching { recorder.stop() }
                    runCatching { recorder.release() }
                    postTakeAutoEnd(end)
                }
                try {
                    while (!holdStopRequested && gen == holdGen) {
                        val count = recorder.read(shorts, 0, shorts.size)
                        if (gen != holdGen) break
                        if (count < 0) {
                            holdError = Exception("Audio capture failed (error code $count)")
                            endTakeAlone(PttEngine.TakeAutoEnd.Failed(holdError?.message ?: "Recording failed"))
                            break
                        }
                        if (count == 0) continue
                        var allZero = true
                        for (i in 0 until count) {
                            val sample = shorts[i].toInt()
                            if (sample != 0) {
                                holdSawSignal = true
                                allZero = false
                            }
                            bytes[i * 2] = (sample and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                        }
                        if (gen != holdGen) break
                        holdPcm.write(bytes, 0, count * 2)
                        if (allZero && !holdSawSignal) {
                            zeroRunMs += HOLD_WINDOW_MS
                            // A dead capture path reads back zeros from the first
                            // window: fail in 1.5 s, not after a full take.
                            if (zeroRunFailsFast(zeroRunMs)) {
                                holdError = Exception(CAPTURE_SILENCE_MESSAGE)
                                endTakeAlone(PttEngine.TakeAutoEnd.Failed(CAPTURE_SILENCE_MESSAGE))
                                break
                            }
                        }
                        _level.value = ((SilenceDetector.rmsDb(shorts, count) + 60f) / 50f).coerceIn(0f, 1f)
                        if (System.currentTimeMillis() - startMs > MAX_VAD_RECORDING_MS) {
                            // Safety cap, and it is loud about itself: what was heard
                            // is kept for Send, and the take ends now on its own.
                            endTakeAlone(PttEngine.TakeAutoEnd.CapReached)
                            break
                        }
                    }
                } catch (e: Exception) {
                    holdError = Exception("Recording failed: ${e.message ?: e.javaClass.simpleName}")
                    endTakeAlone(PttEngine.TakeAutoEnd.Failed(holdError?.message ?: "Recording failed"))
                } finally {
                    _level.value = 0f
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
            true
        } catch (_: Exception) {
            stopRecordingThread()
            holdError = Exception("Recording failed to start")
            false
        }
    }

    /**
     * Stop the hold capture and return its audio as a WAV file for API STT.
     * Null when there is nothing to send; [holdError] says why. A take that
     * already ended itself (failure or cap) stopped its thread, so this just
     * collects what is there.
     */
    fun stopRecording(): File? {
        // The thread may already be gone (self-ended take); joining a dead
        // thread returns at once. The generation moves on either way, so a
        // thread stuck past the join can never write into the next take.
        takeAutoEndListener = null
        holdStopRequested = true
        holdGen++
        try {
            holdThread?.join(3000)
        } catch (_: Exception) {
        }
        holdThread = null
        try {
            holdRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            holdRecorder?.release()
        } catch (_: Exception) {
        }
        holdRecorder = null
        _level.value = 0f
        holdError?.let { return null }
        val pcm = holdPcm.toByteArray()
        return when {
            pcm.isEmpty() -> {
                holdError = Exception(NO_SPEECH_DETECTED)
                null
            }
            !holdSawSignal -> {
                holdError = Exception(CAPTURE_SILENCE_MESSAGE)
                null
            }
            else -> writeWav(context.cacheDir, pcm, 16_000)
        }
    }

    /** Ends the hold capture thread and frees its recorder; the take is discarded. */
    private fun stopRecordingThread() {
        takeAutoEndListener = null
        holdStopRequested = true
        holdGen++
        try {
            holdThread?.join(2000)
        } catch (_: Exception) {
        }
        holdThread = null
        try {
            holdRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            holdRecorder?.release()
        } catch (_: Exception) {
        }
        holdRecorder = null
        holdStopRequested = false
    }

    /** Transcribe audio using the API provider. */
    suspend fun transcribeApi(
        audioFile: File,
        model: String,
        language: String = "",
        contentType: String = "audio/m4a"
    ): Result<String> = withContext(Dispatchers.IO) {
        val api = audioApi ?: return@withContext Result.failure(Exception("API not configured"))
        // Empty means "ask the server": pick the first STT model it advertises, like TranscribeTool does.
        val effectiveModel = model.ifBlank {
            if (apiSttModels.isEmpty()) refreshApiModels()
            apiSttModels.firstOrNull()?.id
                ?: return@withContext Result.failure(
                    Exception(
                        if (api.lastErrorCode == 401 || api.lastErrorCode == 403) {
                            "HTTP ${api.lastErrorCode} listing speech models: the API key was rejected"
                        } else {
                            "No transcription model on the configured endpoint"
                        }
                    )
                )
        }
        try {
            api.transcribe(
                audioFile = audioFile,
                model = effectiveModel,
                language = language,
                contentType = contentType
            )
        } finally {
            audioFile.delete()
        }
    }

    /**
     * Listen for a single hands-free utterance and return its transcript.
     *
     * Unlike the push-to-talk path ([startListening] / [stopListeningAndTranscribe]),
     * this ends on its own once the user has finished speaking:
     *
     * - Android provider: the platform's [SpeechRecognizer] endpoints on end of
     *   speech (with a best-effort silence hint of [silenceTimeoutMs]).
     * - API provider: raw PCM is captured via [AudioRecord] and a local
     *   [SilenceDetector] stops the recording after [silenceTimeoutMs] of
     *   trailing silence; the resulting WAV is transcribed through the API.
     */
    override suspend fun listenForUtterance(
        provider: AudioProvider,
        model: String,
        language: Language,
        silenceTimeoutMs: Long
    ): Result<String> = when {
        provider == AudioProvider.ANDROID -> {
            when (val outcome = listenOnceAndroid(language, silenceTimeoutMs)) {
                is SttOutcome.Text ->
                    if (outcome.text.isNotBlank()) {
                        Result.success(outcome.text)
                    } else {
                        Result.failure(Exception("No speech detected"))
                    }
                is SttOutcome.Error ->
                    Result.failure(Exception(androidFailure ?: "Speech recognition failed: ${outcome.code}"))
            }
        }
        provider.isApiBased() -> withContext(Dispatchers.IO) {
            val wav = recordPcmWithVad(silenceTimeoutMs)
            vadUserStopped = false
            if (wav == null) {
                val cause = vadRecordError
                vadRecordError = null
                Result.failure(cause ?: Exception("No speech detected"))
            } else {
                transcribeApi(wav, model, language.iso639, contentType = "audio/wav")
            }
        }
        else -> Result.failure(Exception("STT not configured"))
    }

    /**
     * Records up to [silenceTimeoutMs] of trailing silence after speech using
     * [AudioRecord] (16 kHz mono PCM-16) and returns a WAV file, or null when
     * the user never spoke. Aborts when [cancelListening] is called.
     */
    @SuppressLint("MissingPermission")
    private fun recordPcmWithVad(silenceTimeoutMs: Long): File? {
        val sampleRate = 16_000
        val windowShorts = 3_200 // 200 ms windows
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // MIC, not VOICE_RECOGNITION: the recognition source applies the vendor's
        // capture processing and on some devices reads back pure zeros.
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, windowShorts * 2)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) return null.also {
            vadRecordError = Exception("Recording failed to start")
        }

        vadStopRequested = false
        vadUserStopped = false
        vadRecordError = null
        _vadState.value = null
        val detector = SilenceDetector(silenceTimeoutMs)
        val shortBuf = ShortArray(windowShorts)
        val byteBuf = ByteArray(windowShorts * 2)
        val pcm = ByteArrayOutputStream()
        val startMs = System.currentTimeMillis()
        var result: File? = null
        var peakDb = -50f
        // Any non-zero sample at all. rmsDb clamps at the floor, so a genuinely
        // quiet room is indistinguishable from a broken stream by level alone.
        var sawSignal = false
        try {
            recorder.startRecording()
            while (!vadStopRequested) {
                val count = recorder.read(shortBuf, 0, shortBuf.size)
                if (count < 0) {
                    // A hardware/HAL read failure is not silence: say which it was.
                    vadRecordError = Exception("Audio capture failed (error code $count)")
                    break
                }
                if (count == 0) continue
                for (i in 0 until count) {
                    val sample = shortBuf[i].toInt()
                    if (sample != 0) sawSignal = true
                    byteBuf[i * 2] = (sample and 0xFF).toByte()
                    byteBuf[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }
                pcm.write(byteBuf, 0, count * 2)
                val now = System.currentTimeMillis()
                val db = SilenceDetector.rmsDb(shortBuf, count)
                _level.value = ((db + 60f) / 50f).coerceIn(0f, 1f)
                peakDb = maxOf(peakDb, db)
                val decision = detector.update(db, now)
                _vadState.value = VadSnapshot(detector.floorDb, detector.thresholdDb, peakDb, detector.speechDetected)
                if (decision != SilenceDetector.Decision.CONTINUE ||
                    now - startMs > MAX_VAD_RECORDING_MS
                ) {
                    break
                }
            }
            // An explicit Stop sends whatever was captured: the VAD only controls
            // the hands-free auto-stop, it never vetoes the user's finger. Pure
            // silence still returns null, so an untouched mic doesn't upload
            // 15 s of nothing.
            if (!sawSignal && pcm.size() > 0) {
                // Not one non-zero sample: the capture path itself is broken (the
                // stream opened and read back only zeros). Say so, rather than
                // blaming the user for not speaking.
                vadRecordError = Exception(CAPTURE_SILENCE_MESSAGE)
            } else if (shouldKeepRecording(detector.speechDetected, vadUserStopped, pcm.size())) {
                result = writeWav(context.cacheDir, pcm.toByteArray(), sampleRate)
            }
        } catch (e: Exception) {
            // A permissions/security failure, OOM, or recorder error must not
            // look like "no speech" without a trace — log it for debugging.
            Log.w(TAG, "VAD recording failed; treating as no speech", e)
            vadRecordError = Exception("Recording failed: ${e.message ?: e.javaClass.simpleName}")
            result = null
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
                // Never started or already stopped.
            }
            recorder.release()
            vadStopRequested = false
            vadUserStopped = false
            _level.value = 0f
        }
        return result
    }

    // ---- Android STT (SpeechRecognizer) ----

    /**
     * Start Android SpeechRecognizer. User speaks, then calls stopAndroidListening().
     * [onFailure] hears at once, on the main thread, when the session fails in a
     * way the user has to fix; without it the reason waits in [androidFailure].
     */
    fun startAndroidListening(
        language: Language = Language.ENGLISH,
        onFailure: ((String) -> Unit)? = null
    ): Boolean {
        if (isContinuousListening) return false
        androidFailure = null
        sessionFailure = onFailure
        sessionTag = null
        if (androidOnDeviceOnly && !AndroidSpeechInfo.hasOnDeviceRecognition(context)) {
            androidFailure = context.getString(R.string.stt_on_device_unavailable)
            return false
        }
        currentLanguage = language
        listenGate = CompletableDeferred<String>()
        isContinuousListening = true
        continuousTranscript.clear()
        currentPartialResult = ""

        muteBeep()
        mainHandler.post { startContinuousListeningCycle() }
        return true
    }

    private fun startContinuousListeningCycle() {
        if (!isContinuousListening) {
            // Fix race: if stop was called between cycles, complete the gate now
            listenGate?.complete(buildFinalTranscript())
            return
        }
        currentPartialResult = "" // Reset for new segment
        if (recognizerStale) {
            recognizerStale = false
            try {
                currentRecognizer?.destroy()
            } catch (_: Exception) {}
            currentRecognizer = null
        }
        var recognizer = currentRecognizer
        if (recognizer == null) {
            val newRec = createAndroidRecognizer()
            if (newRec == null) {
                failSession(context.getString(R.string.stt_on_device_unavailable))
                return
            }
            currentRecognizer = newRec
            recognizer = newRec
            newRec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    MasalaLog.d(TAG) { "onReadyForSpeech" }
                }
                override fun onBeginningOfSpeech() { MasalaLog.d(TAG) { "onBeginningOfSpeech" } }
                override fun onRmsChanged(rmsdB: Float) { _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { MasalaLog.d(TAG) { "onEndOfSpeech" } }
                override fun onError(error: Int) {
                    MasalaLog.d(TAG) { "onError: $error" }
                    AndroidSpeechInfo.fatalError(context, error, currentLanguage, androidOnDeviceOnly)?.let {
                        failSession(it)
                        return
                    }
                    if (error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_SERVER
                    ) {
                        try {
                            currentRecognizer?.destroy()
                        } catch (_: Exception) {}
                        currentRecognizer = null
                    }
                    if (isContinuousListening) {
                        mainHandler.post { startContinuousListeningCycle() }
                    } else {
                        unmuteBeep()
                        listenGate?.complete(buildFinalTranscript())
                    }
                }
                override fun onResults(results: Bundle?) {
                    MasalaLog.d(TAG) { "onResults" }
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        if (continuousTranscript.isNotEmpty()) continuousTranscript.append(" ")
                        continuousTranscript.append(text)
                        _heard.value = _heard.value + 1
                    }
                    currentPartialResult = "" // Final result supersedes partial
                    if (isContinuousListening) {
                        mainHandler.post { startContinuousListeningCycle() }
                    } else {
                        unmuteBeep()
                        listenGate?.complete(buildFinalTranscript())
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    MasalaLog.d(TAG) { "onPartialResults" }
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        currentPartialResult = text
                        _heard.value = _heard.value + 1
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) { MasalaLog.d(TAG) { "onEvent: $eventType" } }

                override fun onSegmentResults(segmentResults: Bundle) {
                    MasalaLog.d(TAG) { "onSegmentResults" }
                    val matches = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        if (continuousTranscript.isNotEmpty()) continuousTranscript.append(" ")
                        continuousTranscript.append(text)
                    }
                    currentPartialResult = ""
                }

                override fun onEndOfSegmentedSession() {
                    MasalaLog.d(TAG) { "onEndOfSegmentedSession" }
                    if (isContinuousListening) {
                        mainHandler.post { startContinuousListeningCycle() }
                    } else {
                        unmuteBeep()
                        listenGate?.complete(buildFinalTranscript())
                    }
                }
            })
        }
        val active = recognizer ?: return
        listenWithInstalledTag(active, currentLanguage) { tag ->
            // Stopped, or replaced, while the recognizer was asked about its languages.
            if (isContinuousListening && currentRecognizer === active) startCycle(active, tag)
        }
    }

    private fun startCycle(recognizer: SpeechRecognizer, tag: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // Prevent auto-stopping on pauses to mimic API-based continuous recording
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 20000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 20000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 20000L)
            putExtra("android.speech.extra.DICTATION_MODE", true)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
            }
        }
        try {
            muteBeep()
            recognizer.startListening(intent)
        } catch (_: Exception) {
            unmuteBeep()
            isContinuousListening = false
            listenGate?.complete(buildFinalTranscript())
        }
    }

    /** Stop Android SpeechRecognizer and return the recognized text. */
    suspend fun stopAndroidListening(): String {
        isContinuousListening = false
        val rec = currentRecognizer
        if (rec != null) {
            rec.stopListening()
            // Wait for onResults to fire and complete the gate, with a timeout
            // to avoid hanging if the recognizer is unresponsive or between cycles.
            val gateResult = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                listenGate?.await()
            }
            if (gateResult != null) {
                unmuteBeep()
                listenGate = null
                // The session is over: drop the recognizer so the next tap doesn't meet a
                // stale one and fail with RECOGNIZER_BUSY.
                mainHandler.post {
                    try {
                        currentRecognizer?.cancel()
                        currentRecognizer?.destroy()
                    } catch (_: Exception) {
                    }
                    currentRecognizer = null
                }
                return gateResult
            }
            // Timed out — force cleanup
            mainHandler.post {
                unmuteBeep()
                currentRecognizer?.cancel()
                currentRecognizer?.destroy()
                currentRecognizer = null
            }
        }
        // Recognizer was null (between cycles) or timed out — return what we have
        val result = buildFinalTranscript()
        unmuteBeep()
        listenGate?.complete(result)
        listenGate = null
        return result
    }

    /**
     * Build the final transcript by appending the current in-flight partial
     * result (if any) to the accumulated finalized segments.
     */
    private fun buildFinalTranscript(): String {
        val base = continuousTranscript.toString()
        val partial = currentPartialResult
        currentPartialResult = ""
        if (partial.isBlank()) return base
        return if (base.isNotBlank()) "$base $partial" else partial
    }

    /**
     * Listen for a single utterance hands-free: the recognizer endpoints on
     * its own (no stop call needed) and this returns the transcript or the
     * SpeechRecognizer error code. Used by the voice-call loop.
     */
    suspend fun listenOnceAndroid(
        language: Language = Language.ENGLISH,
        silenceTimeoutMs: Long = 2000L
    ): SttOutcome {
        if (currentRecognizer != null || onceGate != null) {
            return SttOutcome.Error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        }
        val gate = CompletableDeferred<SttOutcome>()
        onceGate = gate
        androidFailure = null
        // SpeechRecognizer must be created and driven from the main thread.
        withContext(Dispatchers.Main) {
            try {
                val recognizer = createAndroidRecognizer()
                if (recognizer == null) {
                    androidFailure = context.getString(R.string.stt_on_device_unavailable)
                    gate.complete(SttOutcome.Error(SpeechRecognizer.ERROR_CLIENT))
                    return@withContext
                }
                muteBeep()
                currentRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        _level.value = smoothed(previous = _level.value, raw = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        unmuteBeep()
                        AndroidSpeechInfo.fatalError(context, error, language, androidOnDeviceOnly)
                            ?.let { androidFailure = it }
                        gate.complete(SttOutcome.Error(error))
                        currentRecognizer?.destroy()
                        currentRecognizer = null
                    }
                    override fun onResults(results: Bundle?) {
                        unmuteBeep()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        gate.complete(SttOutcome.Text(matches?.firstOrNull() ?: ""))
                        currentRecognizer?.destroy()
                        currentRecognizer = null
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onSegmentResults(segmentResults: Bundle) {}
                    override fun onEndOfSegmentedSession() {}
                })
                val tag = suspendCancellableCoroutine { cont ->
                    AndroidSpeechInfo.installedTag(context, recognizer, language) { if (cont.isActive) cont.resume(it) }
                } ?: language.bcp47
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                    // Best-effort end-of-speech hint; the platform may ignore it.
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, silenceTimeoutMs)
                }
                recognizer.startListening(intent)
            } catch (_: Exception) {
                unmuteBeep()
                gate.complete(SttOutcome.Error(SpeechRecognizer.ERROR_CLIENT))
                currentRecognizer = null
            }
        }
        return try {
            gate.await()
        } finally {
            onceGate = null
        }
    }

    // ---- Provider-agnostic PTT ----

    /**
     * Start listening with the current provider. Returns false on failure.
     * [onAndroidFailure] is passed to [startAndroidListening].
     */
    override fun startListening(
        provider: AudioProvider,
        language: Language,
        onAndroidFailure: ((String) -> Unit)?
    ): Boolean {
        return when {
            provider == AudioProvider.ANDROID -> startAndroidListening(language, onAndroidFailure)
            provider.isApiBased() -> startRecording()
            else -> false
        }
    }

    /**
     * Stop recording/listening and transcribe audio using [provider] and [model].
     * For API: stops the recorder, transcribes, returns the text or error.
     */
    override suspend fun stopListeningAndTranscribe(
        provider: AudioProvider,
        model: String,
        language: String
    ): Result<String> {
        return when {
            provider == AudioProvider.ANDROID -> {
                val text = stopAndroidListening()
                if (text.isBlank()) {
                    Result.failure(Exception(androidFailure ?: NO_SPEECH_DETECTED))
                } else {
                    Result.success(text)
                }
            }
            provider.isApiBased() -> {
                val file = stopRecording()
                if (file == null) {
                    val cause = holdError
                    holdError = null
                    return Result.failure(cause ?: Exception("Recording failed"))
                }
                transcribeApi(file, model, language, contentType = "audio/wav")
            }
            else -> Result.failure(Exception("STT not configured"))
        }
    }

    /** Ends a [listenForUtterance] early: the recorder stops and what was said is still transcribed. */
    override fun finishUtterance() {
        vadUserStopped = true
        vadStopRequested = true
        mainHandler.post {
            try {
                currentRecognizer?.stopListening()
            } catch (_: Exception) { }
        }
    }

    /**
     * Abort an active listen (pause/end of a call). Handles both providers.
     * Safe to call from any thread.
     */
    override fun cancelListening(provider: AudioProvider) {
        when {
            provider == AudioProvider.ANDROID -> {
                isContinuousListening = false
                currentPartialResult = ""
                onceGate?.complete(SttOutcome.Error(ERROR_CANCELLED))
                listenGate?.complete("")
                listenGate = null
                mainHandler.post {
                    unmuteBeep()
                    try {
                        currentRecognizer?.cancel()
                        currentRecognizer?.destroy()
                    } catch (_: Exception) { }
                    currentRecognizer = null
                }
            }
            provider.isApiBased() -> {
                stopRecordingThread()
                holdError = null
                vadStopRequested = true
            }
            else -> {}
        }
    }

    /**
     * Silences the recognizer's own start/stop tones. Only the system stream is muted:
     * muting music, notifications or the ringer would let a tap on the mic swallow a
     * song or an incoming call.
     */
    /**
     * A level that moves like a voice rather than flickering: the recognizer's raw value
     * jumps several steps per second, which made the bars saturate and oscillate.
     */
    private fun smoothed(previous: Float, raw: Float): Float = (previous + (raw - previous) * 0.35f).coerceIn(0f, 1f)

    private fun muteBeep() {
        if (!isMutedForRestart) {
            try {
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
            } catch (_: Exception) {}
            isMutedForRestart = true
        }
    }

    private fun unmuteBeep() {
        if (isMutedForRestart) {
            try {
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {}
            isMutedForRestart = false
        }
    }

    companion object {
        private const val TAG = "SttEngine"

        /**
         * Whether a VAD recording is worth transcribing: the detector accepted speech,
         * or the user pressed Stop with audio actually in the buffer. Pure silence
         * (nothing captured, or stop tapped instantly) stays null so it never uploads
         * or transcribes.
         */
        internal fun shouldKeepRecording(speechDetected: Boolean, userStopped: Boolean, pcmBytes: Int): Boolean =
            pcmBytes > 0 && (speechDetected || userStopped)

        /** Synthetic error code: listening was cancelled by pause/end, not by the recognizer. */
        const val ERROR_CANCELLED = -100

        /** Hold-to-talk capture window: 3,200 samples at 16 kHz is one 200 ms window. */
        private const val HOLD_WINDOW_SHORTS = 3_200
        private const val HOLD_WINDOW_MS = 200L

        /** A capture stuck at zero fails after this long of leading silence, not a full take. */
        internal const val FAIL_FAST_ZERO_MS = 1_500L

        /**
         * True once [zeroRunMs] of leading all-zero capture has passed: the
         * microphone is returning silence, not the user staying quiet (a window
         * with any signal at all resets the run, so this only fires first).
         */
        internal fun zeroRunFailsFast(zeroRunMs: Long): Boolean = zeroRunMs >= FAIL_FAST_ZERO_MS

        /** The stream opened and read back only zeros: the capture path is broken. */
        internal const val CAPTURE_SILENCE_MESSAGE = "Recording captured no audio (microphone returned silence)"


        /** Hard cap on a single hands-free VAD recording (90 s). */
        private const val MAX_VAD_RECORDING_MS = 90_000L

        /**
         * True when [error] is the benign "the user stayed quiet" outcome rather
         * than a real STT failure (network, permissions, recognizer busy, ...).
         * A hands-free listen loop must treat silence as a quiet retry signal and
         * surface genuine failures to the user instead of silently dying.
         */
        fun isBenignSttError(error: Throwable?): Boolean {
            if (error == null) return true
            val message = error.message ?: return false
            if (message == NO_SPEECH_DETECTED) return true
            val code = message.removePrefix(SPEECH_FAILED_PREFIX).toIntOrNull()
            return code != null && SttOutcome.Error(code).isBenign
        }

        private const val NO_SPEECH_DETECTED = "No speech detected"
        private const val SPEECH_FAILED_PREFIX = "Speech recognition failed: "
    }
}

private fun writeWav(cacheDir: File, pcm: ByteArray, sampleRate: Int): File {
    // UUID suffix so two recordings created in the same millisecond never collide.
    val file = File(
        cacheDir,
        "stt_hands_free_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.wav"
    )
    file.outputStream().use { out ->
        val dataSize = pcm.size
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeIntLittleEndian(header, 4, 36 + dataSize)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeIntLittleEndian(header, 16, 16)
        writeShortLittleEndian(header, 20, 1)
        writeShortLittleEndian(header, 22, 1)
        writeIntLittleEndian(header, 24, sampleRate)
        writeIntLittleEndian(header, 28, sampleRate * 2)
        writeShortLittleEndian(header, 32, 2)
        writeShortLittleEndian(header, 34, 16)
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeIntLittleEndian(header, 40, dataSize)
        out.write(header)
        out.write(pcm)
    }
    return file
}

private fun writeIntLittleEndian(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
    bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun writeShortLittleEndian(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
}
