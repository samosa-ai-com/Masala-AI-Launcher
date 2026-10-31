package com.samosaai.masala.core.agent

import java.net.URI

/**
 * Which pages a run nobody watches may open (plan `phase5-daily-brief.md` B3): only addresses a search
 * earlier in the same run returned ([SOURCES]). Such a run holds private data (calendar, topics), so a
 * URL the model builds itself, or one a calendar entry or page names, could carry that data out.
 * Matching ignores the host's case, a leading `www.`, the fragment, a trailing slash and `utm_*`
 * parameters. The scheme counts, except that a result seen over http may be opened over https.
 */
object FetchAllowance {
    const val TOOL = "webfetch"

    /** The tools whose results name pages that may be opened. */
    val SOURCES = setOf("websearch")

    const val REFUSAL = "Only pages from this run's search results can be opened. Use a URL exactly as websearch returned it."

    private val URL = Regex("https?://[^\\s)\\]>\"'<]+")

    private data class Address(val scheme: String, val rest: String)

    /** True when [url] is one of the addresses in [searchOutputs] (the text of [SOURCES] results). */
    fun allows(url: String, searchOutputs: List<String>): Boolean {
        val wanted = normalize(url) ?: return false
        return searchOutputs.any { output ->
            URL.findAll(output).mapNotNull { normalize(it.value.trimEnd('.', ',', ';')) }.any { seen ->
                seen.rest == wanted.rest && (seen.scheme == wanted.scheme || wanted.scheme == "https")
            }
        }
    }

    private fun normalize(url: String): Address? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val port = if (uri.port == -1 || uri.port == 80 || uri.port == 443) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.split('&')?.filterNot { it.isEmpty() || it.lowercase().startsWith("utm_") }?.joinToString("&").orEmpty()
        return Address(scheme, host + port + path + if (query.isEmpty()) "" else "?$query")
    }
}
