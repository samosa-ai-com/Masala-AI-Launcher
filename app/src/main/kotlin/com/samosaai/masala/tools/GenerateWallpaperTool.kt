package com.samosaai.masala.tools

import android.content.Context
import android.graphics.BitmapFactory
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.llm.AiConnection
import com.samosaai.masala.llm.AiSettingsStore
import com.samosaai.masala.wallpapers.WallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Generates a phone wallpaper from a text description:
 * POSTs the configured image endpoint (`/images/generations`), saves the picture under
 * `filesDir/wallpapers/`, records its prompt in the [WallpaperStore] catalog (searchable),
 * and optionally applies it through [WallpaperTool]. A blank configured model means "ask the
 * server": the first image model its `/models` lists. Generation is slow, so the client
 * allows minutes, not seconds.
 */
class GenerateWallpaperTool(
    private val context: Context,
    private val settings: AiSettingsStore,
    private val wallpaper: WallpaperTool,
    private val store: WallpaperStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Generates a portrait wallpaper for [prompt] (+ optional [style] appended). With [target]
     * (`home`/`lock`/`both`) the fresh wallpaper is also applied; otherwise it is only saved
     * and previewed, and the caller pairs this with `set_wallpaper`.
     */
    suspend fun generate(prompt: String, style: String? = null, target: String? = null): ActionResult =
        withContext(Dispatchers.IO) {
            val trimmed = prompt.trim()
            if (trimmed.isEmpty()) return@withContext ActionResult.error("Describe the wallpaper first.")
            val cfg = settings.imageConfig()
                ?: return@withContext ActionResult.error(
                    "Image generation isn't set up yet. Pick a provider in Settings → Masala AI → " +
                        "Models & keys, and turn on Wallpaper generation.",
                )
            val model = cfg.imageModel.ifBlank { discoverModel(cfg.baseUrl, cfg.apiKey) }
                ?: return@withContext ActionResult.error(
                    "The image endpoint lists no image model. Check the model in Settings → Masala AI.",
                )
            val fullPrompt = listOf(trimmed, style?.trim().orEmpty())
                .filter { it.isNotEmpty() }.joinToString(", ") + ", phone wallpaper, no text unless asked"
            val bytes = requestImage(cfg.baseUrl, cfg.apiKey, model, fullPrompt).fold(
                onSuccess = { it },
                onFailure = { return@withContext ActionResult.error(it.message ?: "Couldn't generate the wallpaper.") },
            )
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext ActionResult.error("The generated file isn't a readable image.")
            }
            val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
            val file = File(dir, "wallpaper-${System.currentTimeMillis()}.png")
            runCatching { file.writeBytes(bytes) }.onFailure {
                return@withContext ActionResult.error("Couldn't save the generated wallpaper.")
            }
            store.add(file.name, trimmed, style?.trim().orEmpty())
            // Pruning lives in the store so rows never outlive their files (or vice versa).
            store.prune(KEEP)
            if (!target.isNullOrBlank()) {
                val applied = wallpaper.setWallpaper(file.absolutePath, target)
                if (!applied.success) return@withContext applied
            }
            val where = when (target?.lowercase()) {
                "lock" -> " the lock screen"
                "both" -> " home and lock screens"
                "home", null, "" -> if (target.isNullOrBlank()) "" else " the home screen"
                else -> ""
            }
            ActionResult.ok(
                "Generated a wallpaper with $model$where.",
                produced = listOf(Produced("wallpaper", trimmed.take(80), file.absolutePath)),
            )
        }

    /** First image model the endpoint lists, or null. Pure HTTP; never throws. */
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
                put("size", "1024x1792")
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

    /**
     * Downloads a generated picture. The key goes only to the endpoint itself: image URLs
     * are usually presigned object-store links, which both must not receive the secret and
     * reject requests carrying an unexpected Authorization header.
     */
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

    companion object {
        private const val KEEP = 10
        private const val MAX_BYTES = 32L * 1024 * 1024
    }
}
