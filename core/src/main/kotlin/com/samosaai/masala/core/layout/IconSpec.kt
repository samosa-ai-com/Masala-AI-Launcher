package com.samosaai.masala.core.layout

/**
 * One chosen icon, as it is stored: `symbol:<name>` for a built-in, or one small inline SVG. Mini
 * apps, app overrides and anything else that remembers an icon share this, so the limits are one set
 * rather than a copy per feature.
 *
 * An SVG never loads anything: no external file resolver is registered anywhere, and [sanitize]
 * refuses remote references before the text is stored at all.
 */
object IconSpec {
    /** Anything longer is refused: a launcher row needs a glyph, not an illustration. */
    const val MAX_CHARS = 8_192

    const val SYMBOL_PREFIX = "symbol:"

    private val BLOCKED = listOf("<script", "<foreignobject", "<image", "href=\"http", "href='http", "url(http")

    /** An XML declaration, doctype or comment before the `<svg>` tag, as models often write. */
    private val PROLOG = Regex(
        """^(\s*(<\?xml[^>]*\?>|<!DOCTYPE[^>]*>|<!--.*?-->))*\s*""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * What gets stored: `symbol:<name>` for a built-in, the SVG (from its `<svg` tag on), or null when
     * [raw] is blank, too long, not an SVG, or reaches for anything remote.
     */
    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (!trimmed.startsWith("<")) {
            val name = trimmed.removePrefix(SYMBOL_PREFIX)
            return if (name in KnownSymbols.all) "$SYMBOL_PREFIX$name" else null
        }
        val svg = trimmed.replaceFirst(PROLOG, "")
        if (svg.length > MAX_CHARS || !svg.startsWith("<svg")) return null
        if (BLOCKED.any { it in svg.lowercase() }) return null
        return svg
    }

    /** Whether [spec] is a built-in symbol rather than an SVG. */
    fun isSymbol(spec: String?): Boolean = spec?.startsWith(SYMBOL_PREFIX) == true

    /** The built-in name inside a symbol [spec]; null for anything else. */
    fun symbolName(spec: String?): String? = spec?.takeIf(::isSymbol)?.removePrefix(SYMBOL_PREFIX)
}
