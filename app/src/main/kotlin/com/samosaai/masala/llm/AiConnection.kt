package com.samosaai.masala.llm

import com.samosaai.masala.audio.AudioModel
import com.samosaai.masala.audio.ModelCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetch list and Test connection for Settings → Models & keys (plan UI refresh U2): one `GET /models`
 * against a base URL and key, timed, with each model sorted into chat, transcription or voice by the
 * same classification the audio engines use. Provider facts change, so this decides what a preset
 * offers; the preset only sets the default toggles.
 */
class AiConnection(private val client: OkHttpClient = defaultClient) {
    data class Found(
        val chat: List<String>,
        val transcription: List<String>,
        val voices: List<String>,
        val millis: Long,
    )

    sealed interface Outcome {
        data class Connected(val found: Found) : Outcome
        /** 401 or 403: the server is there but refused the key. */
        data class KeyRefused(val code: Int) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun test(baseUrl: String, apiKey: String): Outcome = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        runCatching {
            val request = Request.Builder().url(baseUrl.trimEnd('/') + "/models").get().apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }.build()
            client.newCall(request).execute().use { response ->
                val millis = (System.nanoTime() - started) / 1_000_000
                when {
                    response.code == 401 || response.code == 403 -> Outcome.KeyRefused(response.code)
                    !response.isSuccessful -> Outcome.Failed("HTTP ${response.code}")
                    else -> Outcome.Connected(classify(response.body?.string().orEmpty(), millis))
                }
            }
        }.getOrElse { Outcome.Failed(it.message ?: it.javaClass.simpleName) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /** Sorts the ids in a `/models` reply; unknown ones count as chat. Pure, for tests. */
        fun classify(body: String, millis: Long): Found {
            val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonArray }.getOrNull().orEmpty()
            val byKind = data.mapNotNull { element ->
                val o = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val kind = AudioModel.categorize(id, o["task"]?.jsonPrimitive?.contentOrNull, o["provider_type"]?.jsonPrimitive?.contentOrNull)
                id to kind
            }.groupBy({ it.second }, { it.first })
            return Found(
                chat = (byKind[ModelCategory.LLM].orEmpty() + byKind[ModelCategory.UNKNOWN].orEmpty()).sorted(),
                transcription = byKind[ModelCategory.STT].orEmpty().sorted(),
                voices = byKind[ModelCategory.TTS].orEmpty().sorted(),
                millis = millis,
            )
        }
    }
}
