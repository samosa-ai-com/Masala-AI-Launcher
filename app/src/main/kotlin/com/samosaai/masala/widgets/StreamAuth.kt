package com.samosaai.masala.widgets

import java.net.URI

/**
 * HTTP Basic auth for password-protected cameras (review fix B1): `HttpURLConnection` ignores
 * userinfo in the URL, so a protected MJPEG/snapshot camera answered 401. This splits the
 * credentials out and turns them into an `Authorization` header; ExoPlayer paths keep the
 * userinfo URL because the player handles it itself.
 */
object StreamAuth {
    /** The clean URL to open plus the `Authorization` header value, or null when anonymous. */
    fun split(url: String): Pair<String, String?> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url to null
        val info = uri.userInfo?.takeIf { it.isNotEmpty() } ?: return url to null
        val clean = runCatching {
            URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        }.getOrNull() ?: return url to null
        val pair = if (':' in info) info else "$info:"
        val header = "Basic " + java.util.Base64.getEncoder().encodeToString(pair.toByteArray(Charsets.UTF_8))
        return clean to header
    }
}
