package com.samosaai.masala.core.agent

import java.net.URI

/**
 * Which pages a run nobody watches may open (plan `phase5-daily-brief.md` B3): only addresses an earlier
 * tool result in the same run named, such as a search result. Such a run holds private data (calendar,
 * topics), so a URL the model builds itself, or one a calendar entry or page asks for, could carry that
 * data out. Matching ignores the scheme, the host's case, the fragment, a trailing slash and `utm_*`
 * parameters.
 */
object FetchAllowance {
    const val TOOL = "webfetch"
    const val REFUSAL = "Only pages from this run's search results can be opened. Use a URL exactly as websearch returned it."

    private val URL = Regex("https?://[^\\s)\\]>\"'<]+")

    /** True when [url] is one of the addresses in [toolOutputs]. */
    fun allows(url: String, toolOutputs: List<String>): Boolean {
        val wanted = normalize(url) ?: return false
        return toolOutputs.any { output -> URL.findAll(output).any { normalize(it.value.trimEnd('.', ',', ';')) == wanted } }
    }

    internal fun normalize(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val port = if (uri.port == -1 || uri.port == 80 || uri.port == 443) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.split('&')?.filterNot { it.isEmpty() || it.lowercase().startsWith("utm_") }?.joinToString("&").orEmpty()
        return host + port + path + if (query.isEmpty()) "" else "?$query"
    }
}
