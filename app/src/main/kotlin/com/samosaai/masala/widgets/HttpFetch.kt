package com.samosaai.masala.widgets

import com.samosaai.masala.core.contract.HttpBinding
import com.samosaai.masala.core.widgets.JsonPick
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * One web value fetch (plan `docs/plans/widget-maker.md` §5): GETs the URL (at most [MAX_BYTES]),
 * picks [HttpBinding.jsonPath] and stringifies it for the card. Failures return null, so the card
 * keeps the last value with its age.
 */
object HttpFetch {
    const val MAX_BYTES = 256 * 1024
    const val TIMEOUT_MS = 10_000

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(url: String, jsonPath: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            connection.connect()
            if (connection.responseCode !in 200..299) return@withContext null
            val bytes = connection.inputStream.use { it.readBytesCapped(MAX_BYTES) } ?: return@withContext null
            val root = runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull() ?: return@withContext null
            val picked = runCatching { JsonPick.pick(root, jsonPath) }.getOrNull() ?: return@withContext null
            stringify(picked)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    internal fun stringify(element: JsonElement): String? {
        val primitive = element as? kotlinx.serialization.json.JsonPrimitive ?: return element.toString()
        return when {
            primitive.isString -> primitive.content
            primitive is kotlinx.serialization.json.JsonNull -> null
            else -> primitive.content
        }
    }

    private fun java.io.InputStream.readBytesCapped(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
            if (total >= max) return null
        }
        return out.toByteArray()
    }
}
