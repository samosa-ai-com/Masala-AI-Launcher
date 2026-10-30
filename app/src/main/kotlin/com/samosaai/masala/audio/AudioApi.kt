package com.samosaai.masala.audio

import android.util.Log
import com.samosaai.masala.BuildConfig
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
            .readTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
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
        return try {
            val url = "${baseUrl.trimEnd('/')}/models"
            try { Log.d("AudioApi", "Fetching models from: $url") } catch (_: Throwable) {}
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    try { Log.w("AudioApi", "Models request failed: HTTP ${response.code}") } catch (_: Throwable) {}
                    return@use emptyList()
                }
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
            val jsonObj = jsonParser.parseToJsonElement(responseBody).jsonObject
            // `transcript` is present in every mode; older/plain responses carry `text`.
            jsonObj["transcript"]?.jsonPrimitive?.contentOrNull
                ?: jsonObj["text"]?.jsonPrimitive?.contentOrNull
                ?: ""
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
    }
}
