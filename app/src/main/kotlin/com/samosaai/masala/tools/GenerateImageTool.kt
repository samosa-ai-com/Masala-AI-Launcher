package com.samosaai.masala.tools

import android.content.Context
import android.graphics.BitmapFactory
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.llm.AiConnection
import com.samosaai.masala.llm.AiSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * General image creation (plan `docs/plans/media-tools.md`): the same configured
 * image endpoint as wallpapers, but square-ish output saved under
 * `filesDir/generated/` and returned as Produced("image") for the chat card.
 * Portrait wallpaper stays in [GenerateWallpaperTool].
 */
class GenerateImageTool(
    private val context: Context,
    private val settings: AiSettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generate(prompt: String, style: String? = null): ActionResult =
        withContext(Dispatchers.IO) {
            val trimmed = prompt.trim()
            if (trimmed.isEmpty()) return@withContext ActionResult.error("Describe the image first.")
            val cfg = settings.imageConfig()
                ?: return@withContext ActionResult.error(
                    "Image generation isn't set up yet. Pick a provider in Settings → Masala AI → " +
                        "Models & keys, and turn on image generation.",
                )
            val model = cfg.imageModel.ifBlank { discoverModel(cfg.baseUrl, cfg.apiKey) }
                ?: return@withContext ActionResult.error(
                    "The image endpoint lists no image model. Check the model in Settings → Masala AI.",
                )
            val fullPrompt = listOf(trimmed, style?.trim().orEmpty())
                .filter { it.isNotEmpty() }.joinToString(", ")
            val bytes = requestImage(cfg.baseUrl, cfg.apiKey, model, fullPrompt).fold(
                onSuccess = { it },
                onFailure = { return@withContext ActionResult.error(it.message ?: "Couldn't generate the image.") },
            )
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext ActionResult.error("The generated file isn't a readable image.")
            }
            val dir = File(context.filesDir, "generated").apply { mkdirs() }
            val file = File(dir, "image-${System.currentTimeMillis()}.png")
            runCatching { file.writeBytes(bytes) }.onFailure {
                return@withContext ActionResult.error("Couldn't save the generated image.")
            }
            prune(dir)
            ActionResult.ok(
                "Generated an image with $model.",
                produced = listOf(Produced("image", trimmed.take(80), file.absolutePath)),
            )
        }

    private suspend fun discoverModel(baseUrl: String, apiKey: String): String? = runCatching {
        when (val o = AiConnection().test(baseUrl, apiKey)) {
            is AiConnection.Outcome.Connected -> o.found.images.firstOrNull()
            else -> null
        }
    }.getOrNull()

    private fun requestImage(baseUrl: String, apiKey: String, model: String, prompt: String): Result<ByteArray> =
        runCatching {
            val body = buildJsonObject {
                put("model", model)
                put("prompt", prompt)
                put("size", "1024x1024")
                put("n", 1)
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/images/generations")
                .post(body)
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            http.newCall(req).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
                    throw IllegalStateException("HTTP ${response.code}: ${(detail ?: text).take(200)}")
                }
                val item = json.parseToJsonElement(text).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: throw IllegalStateException("The image endpoint returned no picture.")
                item["b64_json"]?.jsonPrimitive?.content?.let {
                    return@runCatching android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                }
                val url = item["url"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("The image endpoint returned no picture.")
                download(url, apiKey, baseUrl)
            }
        }

    /** Same-host auth only: presigned CDN links must not receive the key. */
    private fun download(url: String, apiKey: String, baseUrl: String): ByteArray {
        val sameHost = runCatching {
            url.toHttpUrl().host.equals(baseUrl.trimEnd('/').toHttpUrl().host, ignoreCase = true)
        }.getOrDefault(false)
        val req = Request.Builder().url(url).get()
            .apply { if (apiKey.isNotBlank() && sameHost) header("Authorization", "Bearer $apiKey") }
            .build()
        http.newCall(req).execute().use { response ->
            val body = response.body ?: throw IllegalStateException("Empty download.")
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} fetching the picture.")
            if (body.contentLength() > MAX_BYTES) throw IllegalStateException("The generated picture is too large.")
            return body.bytes()
        }
    }

    private fun prune(dir: File) {
        dir.listFiles { f -> f.name.startsWith("image-") }
            .orEmpty().sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val KEEP = 20
        private const val MAX_BYTES = 32L * 1024 * 1024
    }
}
