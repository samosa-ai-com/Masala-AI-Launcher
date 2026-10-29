package com.samosaai.masala.audio

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/**
 * Transcription with Android's own speech service, for when no transcription model is configured.
 *
 * `RecognizerIntent.EXTRA_AUDIO_SOURCE` (API 33+) lets an app hand the recognizer a file of audio
 * instead of the microphone. The recognizer wants plain 16-bit PCM, so containers (m4a, mp3, wav)
 * are decoded first with the platform's own `MediaExtractor`/`MediaCodec` — no ffmpeg, no network.
 *
 * Limits worth knowing: the device's speech service must implement the audio-source extras (most
 * Google-certified builds do), recognition stops after the engine's own phrase limits, and quality
 * is below a server model. Callers use it as a fallback, not as the preferred path.
 */
object AndroidTranscriber {

    /** 16 kHz mono is what the recognizers expect from an audio source. */
    private const val SAMPLE_RATE = 16_000
    private const val CHANNELS = 1

    /** Per-service ceiling: a recognizer that never answers must not block the next candidate. */
    private const val ATTEMPT_MS = 60_000L

    fun isAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Transcribes [source], decoding it to PCM first. Returns null when this device can't do it,
     * so the caller can fall back to an error message instead of a wrong one.
     */
    suspend fun transcribe(
        context: Context,
        source: File,
        language: String?,
        timeoutMs: Long = 120_000,
    ): Result<String>? {
        if (!isAvailable(context)) return null
        val pcm = File(source.parentFile ?: context.cacheDir, source.nameWithoutExtension + ".pcm")
        return try {
            // Decoding is heavy: keep it off the main thread.
            val decoded = withContext(Dispatchers.IO) { decodeToPcm(source, pcm) }
            if (!decoded) return null
            // SpeechRecognizer may only be created and driven from the main thread; any other
            // thread throws "should be used only from the application's main thread".
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(timeoutMs) { recognizeAny(context, pcm, language) }
                    ?: Result.failure(IllegalStateException("Android's speech service timed out"))
            }
        } finally {
            runCatching { pcm.delete() }
        }
    }

    /**
     * Decodes any platform-supported audio track into 16-bit mono PCM at [SAMPLE_RATE], wrapped in
     * a RIFF/WAVE header so a server that demands WAV can take it. The transcription endpoint
     * accepts only mono 16-bit PCM WAV, and recordings here are AAC.
     */
    fun toWav(source: File, target: File): Boolean {
        val pcm = File(source.parentFile ?: target.parentFile, source.nameWithoutExtension + ".tmp.pcm")
        return try {
            if (!decodeToPcm(source, pcm)) return false
            writeWav(pcm, target)
            true
        } finally {
            runCatching { pcm.delete() }
        }
    }

    /** RIFF/WAVE header (44 bytes) in front of raw PCM. */
    private fun writeWav(pcm: File, target: File) {
        val dataSize = pcm.length().toInt()
        val byteRate = SAMPLE_RATE * CHANNELS * 2
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort((CHANNELS * 2).toShort())
        header.putShort(16) // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)
        target.outputStream().use { out ->
            out.write(header.array())
            pcm.inputStream().use { it.copyTo(out) }
        }
    }

    /** Decodes any platform-supported audio track into 16-bit mono PCM at [SAMPLE_RATE]. */
    private fun decodeToPcm(source: File, target: File): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            target.outputStream().buffered().use { out ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex >= 0 -> {
                            val outBuffer = codec.getOutputBuffer(outIndex)!!
                            val pcmBytes = ByteArray(info.size)
                            outBuffer.get(pcmBytes)
                            outBuffer.clear()
                            val converted = toMono16(pcmBytes, sourceChannels)
                            val resampled = resample(converted, sourceRate, SAMPLE_RATE)
                            out.write(resampled)
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    }
                }
            }
            target.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Drops extra channels by keeping the first one, which is all the recognizer needs. */
    private fun toMono16(bytes: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return bytes
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = shorts.remaining() / channels
        val out = ByteArray(frames * 2)
        val outShorts = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until frames) outShorts.put(i, shorts.get(i * channels))
        return out
    }

    /** Nearest-neighbour resampling: enough for speech, and no dependency. */
    private fun resample(bytes: ByteArray, from: Int, to: Int): ByteArray {
        if (from == to || from <= 0) return bytes
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = input.remaining()
        val targetCount = (count.toLong() * to / from).toInt()
        val out = ByteArray(targetCount * 2)
        val output = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until targetCount) {
            val at = (i.toLong() * from / to).toInt().coerceIn(0, count - 1)
            output.put(i, input.get(at))
        }
        return out
    }

    /**
     * Tries each installed recognition service in turn. The phone's *configured* service is often
     * not the one that can read a file (this Samsung points at a TTS package, which answers
     * ERROR_CLIENT), while Android System Intelligence does. A service that refuses is skipped.
     */
    private suspend fun recognizeAny(context: Context, pcm: File, language: String?): Result<String> {
        val candidates = candidateServices(context)
        var last: Result<String> = Result.failure(IllegalStateException("no speech service installed"))
        for (component in candidates) {
            val attempt = withTimeoutOrNull(ATTEMPT_MS) { recognize(context, component, pcm, language) }
            if (attempt == null) {
                last = Result.failure(IllegalStateException("${component.flattenToShortString()} timed out"))
                continue
            }
            if (attempt.isSuccess) return attempt
            last = attempt
        }
        return last
    }

    /** Installed recognizers, best first: on-device intelligence, then Google's, then the rest. */
    private fun candidateServices(context: Context): List<ComponentName> {
        val intent = Intent("android.speech.RecognitionService")
        val found = context.packageManager.queryIntentServices(intent, 0).mapNotNull { info ->
            info.serviceInfo?.let { ComponentName(it.packageName, it.name) }
        }
        val preferred = listOf(
            "com.google.android.as",
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts",
        )
        return found.sortedBy { component ->
            preferred.indexOfFirst { component.packageName.startsWith(it) }
                .let { if (it < 0) preferred.size else it }
        }
    }

    private suspend fun recognize(
        context: Context,
        component: ComponentName,
        pcm: File,
        language: String?,
    ): Result<String> = suspendCancellableCoroutine { cont ->
        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(context, component)
        }.getOrElse {
            cont.resume(Result.failure(it as Exception))
            return@suspendCancellableCoroutine
        }
        val pfd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
        var finished = false

        fun finish(result: Result<String>) {
            if (finished) return
            finished = true
            // Both calls are main-thread-only, and this whole function runs there.
            runCatching { recognizer.destroy() }
            runCatching { pfd.close() }
            if (cont.isActive) cont.resume(result)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                finish(Result.success(text))
            }

            override fun onError(error: Int) {
                val reason = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "the audio could not be read"
                    SpeechRecognizer.ERROR_CLIENT -> "it rejected the request"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission is missing"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "it needs a network connection"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "it is busy"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "that language isn't installed"
                    SpeechRecognizer.ERROR_SERVER -> "it reported a server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech was detected in the file"
                    else -> "error $error"
                }
                finish(Result.failure(IllegalStateException("${component.flattenToShortString()}: $reason")))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, CHANNELS)
            if (!language.isNullOrBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        runCatching { recognizer.startListening(intent) }
            .onFailure { finish(Result.failure(it as Exception)) }

        cont.invokeOnCancellation { finish(Result.failure(IllegalStateException("cancelled"))) }
    }
}
