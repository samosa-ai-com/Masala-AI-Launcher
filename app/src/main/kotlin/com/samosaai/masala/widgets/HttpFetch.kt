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
 *
 * Redirects are followed by hand, never by the connection: each hop's host must already be in
 * [allowedHosts], so an approved host can't bounce the request somewhere the user never approved.
 * The first URL is the caller's responsibility (the store checks approval before calling).
 */
object HttpFetch {
    const val MAX_BYTES = 256 * 1024
    const val TIMEOUT_MS = 10_000
    private const val MAX_REDIRECTS = 5

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(url: String, jsonPath: String, allowedHosts: Set<String> = emptySet()): String? = withContext(Dispatchers.IO) {
        var current = url
        var hops = 0
        while (true) {
            when (val step = attempt(current, jsonPath, allowedHosts)) {
                is Attempt.Redirect -> {
                    if (hops++ >= MAX_REDIRECTS) return@withContext null
                    current = step.url
                }
                is Attempt.Done -> return@withContext step.value
            }
        }
        @Suppress("UNREACHABLE_CODE") // the loop only exits via return; this types the lambda.
        return@withContext null
    }

    private sealed interface Attempt {
        data class Redirect(val url: String) : Attempt
        data class Done(val value: String?) : Attempt
    }

    private fun attempt(current: String, jsonPath: String, allowedHosts: Set<String>): Attempt {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
            }
            connection.connect()
            if (connection.responseCode in 300..399) {
                val next = connection.headerFields.entries
                    .firstOrNull { (k, _) -> k?.equals("Location", ignoreCase = true) == true }
                    ?.value?.firstOrNull() ?: return Attempt.Done(null)
                val resolved = runCatching { URL(URL(current), next).toString() }.getOrNull() ?: return Attempt.Done(null)
                val host = runCatching { URL(resolved).host?.lowercase()?.trimEnd('.') }.getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: return Attempt.Done(null)
                if (host !in allowedHosts) return Attempt.Done(null)
                return Attempt.Redirect(resolved)
            }
            if (connection.responseCode !in 200..299) return Attempt.Done(null)
            val bytes = connection.inputStream.use { it.readBytesCapped(MAX_BYTES) } ?: return Attempt.Done(null)
            val root = runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull() ?: return Attempt.Done(null)
            val picked = runCatching { JsonPick.pick(root, jsonPath) }.getOrNull() ?: return Attempt.Done(null)
            return Attempt.Done(stringify(picked))
        } catch (e: Exception) {
            return Attempt.Done(null)
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
