package com.samosaai.masala.audio

import android.util.Log
import com.samosaai.masala.BuildConfig
import com.samosaai.masala.core.audio.DiarizedTranscript
import com.samosaai.masala.core.audio.SpeakerTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the audio API (STT + TTS).
 * Uses OkHttp directly (not Retrofit) to handle streaming + multipart uploads easily.
 */
class AudioApi(
    private val baseUrl: String,
    private val apiKey: String,
    private val timeoutSeconds: Long = 30L,
    /** Invoked when the server returns HTTP 401 — fires the Samosa session
     *  invalidation path so the user re-signs in. */
    private val onUnauthorized: (() -> Unit)? = null
) {
    private val client: OkHttpClient
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** HTTP status of the last failed model request (0 when it succeeded), for honest messages. */
    @Volatile
    var lastErrorCode: Int = 0
        private set

    init {
        val logging = HttpLoggingInterceptor().apply {
            // The request URL (the configured provider endpoint) is not user
            // content we should ship to release logcat; keep tracing debug-only.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }
        client = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            // Reads/writes allow a long voice take's WAV (~10 MB at the 5-minute cap).
            .readTimeout(timeoutSeconds * 4, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds * 4, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                if (apiKey.isNotBlank()) {
                    request.addHeader("Authorization", "Bearer $apiKey")
                }
                val response = chain.proceed(request.build())
                if (response.code == 401) {
                    onUnauthorized?.invoke()
                }
                response
            }
            .addInterceptor(logging)
            .build()
    }

    /** Fetch available models from the API and categorize them by the `task` field. */
    @Suppress("CyclomaticComplexMethod")
    fun listAudioModels(): List<AudioModel> {
        lastErrorCode = 0
        return try {
            val url = "${baseUrl.trimEnd('/')}/models"
            try { Log.d("AudioApi", "Fetching models from: $url") } catch (_: Throwable) {}
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    try { Log.w("AudioApi", "Models request failed: HTTP ${response.code}") } catch (_: Throwable) {}
                    lastErrorCode = response.code
                    return@use emptyList()
                }
                lastErrorCode = 0
                val body = response.body?.string() ?: return@use emptyList()
                try {
                    Log.d("AudioApi", "Models response (first 200 chars): ${body.take(200)}")
                } catch (_: Throwable) {
                }
                val jsonObj = jsonParser.parseToJsonElement(body).jsonObject
                val dataArr = jsonObj["data"]?.jsonArray ?: return@use emptyList()
                dataArr.mapNotNull { element ->
                    val modelObj = element.jsonObject
                    val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val task = modelObj["task"]?.jsonPrimitive?.contentOrNull
                    val providerType = modelObj["provider_type"]?.jsonPrimitive?.contentOrNull
                    val category = AudioModel.categorize(id, task, providerType)
                    val languages = when (val langElem = modelObj["language"]) {
                        is kotlinx.serialization.json.JsonArray -> langElem.mapNotNull { l ->
                            l.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
                        }
                        is JsonPrimitive -> listOfNotNull(langElem.contentOrNull?.takeIf { it.isNotBlank() })
                        else -> emptyList()
                    }
                    val voices = if (category == ModelCategory.TTS) {
                        modelObj["voices"]?.jsonArray?.mapNotNull innerMap@{ v ->
                            when (v) {
                                is JsonObject -> {
                                    val vId = v["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                        ?: v["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                        ?: return@innerMap null
                                    val vName = v["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val vLang = v["language"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val vGender = v["gender"]?.jsonPrimitive?.contentOrNull ?: ""
                                    VoiceInfo(id = vId, name = vName, language = vLang, gender = vGender)
                                }
                                is JsonPrimitive -> {
                                    val vId = v.contentOrNull?.takeIf { it.isNotBlank() } ?: return@innerMap null
                                    VoiceInfo(id = vId)
                                }
                                else -> null
                            }
                        } ?: emptyList()
                    } else {
                        emptyList()
                    }
                    AudioModel(id = id, category = category, languages = languages, voices = voices)
                }
            }
        } catch (e: Exception) {
            try { Log.e("AudioApi", "Failed to list models", e) } catch (_: Throwable) {}
            emptyList()
        }
    }

    /**
     * Speech-to-text through the documented ASR endpoint: a multipart request with a JSON
     * `request` part and a WAV `audio` part, answered with a JSON transcript.
     *
     * The WAV must be mono 16-bit PCM at 16 kHz or 24 kHz; callers convert before calling.
     */
    fun transcribeWav(
        wavFile: File,
        model: String,
        language: String? = null,
        mode: String = DEFAULT_ASR_MODE,
    ): Result<String> = runCatching {
        val jsonObj = jsonParser.parseToJsonElement(postAsr(wavFile, model, language, mode)).jsonObject
        // `transcript` is present in every mode; older/plain responses carry `text`.
        jsonObj["transcript"]?.jsonPrimitive?.contentOrNull
            ?: jsonObj["text"]?.jsonPrimitive?.contentOrNull
            ?: ""
    }

    /**
     * Speaker-labelled transcription: the same upload with `mode = DIARIZATION`, answered with
     * `turns[]` (text, startMs, endMs, speaker). Speakers are labelled per request — always starting
     * at `A` for the first voice — so a meeting stitches windows together with
     * [com.samosaai.masala.core.audio.SpeakerLabels]; this call does not pretend otherwise.
     *
     * The endpoint caps one request at ten minutes and times out near two minutes of processing, so
     * callers upload windows of about five minutes. A response without `turns` still carries the flat
     * transcript and comes back as one unlabelled turn.
     */
    fun transcribeDiarized(
        wavFile: File,
        model: String,
        language: String? = null,
    ): Result<DiarizedTranscript> = runCatching {
        parseDiarized(postAsr(wavFile, model, language, DIARIZATION_MODE))
    }

    /** The multipart upload both transcription modes use; keeps the request shape in one place. */
    private fun postAsr(wavFile: File, model: String, language: String?, mode: String): String {
        val url = "${baseUrl.trimEnd('/')}/asr/transcribe"
        val boundary = "Boundary-${System.currentTimeMillis()}"
        val settings = buildJsonObject {
            put("model", model)
            put("mode", mode)
            put("audioEncoding", "WAV")
            val languageName = language?.trim()?.takeIf { it.isNotEmpty() }
            if (languageName != null) putJsonArray("languageBias") { add(languageName) }
        }
        val body = MultipartBody.Builder(boundary)
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "request",
                null,
                settings.toString().toRequestBody("application/json".toMediaType()),
            )
            .addFormDataPart("audio", wavFile.name, wavFile.asRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw IOException("Empty response")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${responseBody.take(300)}")
            return responseBody
        }
    }

    /**
     * Transcription for callers that already hold a recording in any format: converts it to the
     * WAV the endpoint requires, then uploads. Kept as the single entry point for live dictation.
     */
    fun transcribe(
        audioFile: File,
        model: String,
        language: String? = null,
        contentType: String = "audio/m4a",
    ): Result<String> {
        val wav = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".asr.wav")
        return try {
            if (!AndroidTranscriber.toWav(audioFile, wav)) {
                return Result.failure(IOException("Couldn't convert ${audioFile.name} to WAV"))
            }
            transcribeWav(wav, model, language)
        } finally {
            runCatching { wav.delete() }
        }
    }

    /**
     * Text-to-speech: synthesize speech and return audio bytes.
     * @param voice must be a valid voice ID from the model's voice list.
     */
    fun synthesize(text: String, model: String, voice: String, speed: Float = 1f): Result<ByteArray> = runCatching {
        val url = "${baseUrl.trimEnd('/')}/audio/speech"
        val json = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "wav")
            if (speed != 1f) put("speed", speed)
        }
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: "no body"
                throw IOException("HTTP ${response.code}: $errBody")
            }
            response.body?.bytes() ?: throw IOException("Empty response body")
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        file: File,
        fieldName: String,
        contentType: String,
        extraFields: Map<String, String>
    ): RequestBody {
        val delimiter = "--$boundary\r\n".toByteArray()
        val closing = "--$boundary--\r\n".toByteArray()
        val bos = java.io.ByteArrayOutputStream()

        for ((key, value) in extraFields) {
            bos.write(delimiter)
            bos.write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n".toByteArray())
            bos.write("$value\r\n".toByteArray())
        }

        bos.write(delimiter)
        bos.write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"\r\n".toByteArray())
        bos.write("Content-Type: $contentType\r\n\r\n".toByteArray())
        bos.write(file.readBytes())
        bos.write("\r\n".toByteArray())
        bos.write(closing)

        return bos.toByteArray().toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())
    }

    companion object {
        /**
         * PUSH_TO_TALK returns one transcript for the whole file, which is what a recorded meeting
         * note or voice memo wants. ENDPOINTING and DIARIZATION split it into turns instead.
         */
        const val DEFAULT_ASR_MODE = "PUSH_TO_TALK"

        /** Turns with speakers. The endpoint's own limit is ten minutes per request. */
        const val DIARIZATION_MODE = "DIARIZATION"

        /** `speaker_B|>` is an artefact the endpoint sometimes emits as a turn of its own. */
        private val SPEAKER_ARTIFACT = Regex("(?i)^speaker_[a-z]\\|?>?$")

        /**
         * Reads an `/asr/transcribe` answer with `turns`. Kept separate from the network call so the
         * shapes can be tested against captured responses.
         */
        fun parseDiarized(body: String): DiarizedTranscript {
            val parser = Json { ignoreUnknownKeys = true; isLenient = true }
            val obj = parser.parseToJsonElement(body).jsonObject
            val duration = obj["audioDurationMs"]?.jsonPrimitive?.longOrNull ?: 0L
            val turns = obj["turns"]?.jsonArray?.mapNotNull { element ->
                val turn = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val text = (turn["transcript"] ?: turn["text"])?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                // The endpoint can echo a speaker token as a turn; it is not speech.
                if (SPEAKER_ARTIFACT.matches(text)) return@mapNotNull null
                val start = turn["startMs"]?.jsonPrimitive?.longOrNull ?: 0L
                val end = turn["endMs"]?.jsonPrimitive?.longOrNull ?: start
                SpeakerTurn(
                    startMs = start,
                    endMs = end.coerceAtLeast(start),
                    speaker = turn["speaker"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
                    text = text,
                )
            }.orEmpty()
            if (turns.isNotEmpty()) return DiarizedTranscript(turns, duration.ifZero(turns.last().endMs))
            // No turns: a flat transcript is still better than nothing, as one unlabelled turn.
            val flat = (obj["transcript"] ?: obj["text"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            return if (flat.isEmpty()) {
                DiarizedTranscript(emptyList(), duration)
            } else {
                DiarizedTranscript(listOf(SpeakerTurn(0, duration, null, flat)), duration)
            }
        }

        private fun Long.ifZero(other: Long): Long = if (this > 0) this else other
    }
}
