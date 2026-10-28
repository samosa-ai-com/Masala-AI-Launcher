package com.samosaai.masala.ui.components

/**
 * The Markdown the chat shows: top-level fenced code blocks are split out first, since the renderer
 * draws code without a copy button; everything else is Markdown for the renderer (see `Markdown.kt`).
 */
sealed interface MarkdownSegment {
    data class Prose(val markdown: String) : MarkdownSegment
    data class Code(val code: String, val language: String?) : MarkdownSegment
}

object MarkdownParser {
    private val openingFence = Regex("^( {0,3})(`{3,}|~{3,})\\s*([^\\s`]*)[^`]*$")

    /** Splits at top-level fenced code blocks (CommonMark rules). An unclosed fence runs to the end, as a streamed reply looks. */
    fun segments(markdown: String): List<MarkdownSegment> {
        val segments = mutableListOf<MarkdownSegment>()
        val prose = StringBuilder()
        fun flush() {
            val text = prose.toString().trim('\n')
            if (text.isNotBlank()) segments += MarkdownSegment.Prose(text)
            prose.setLength(0)
        }
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val open = openingFence.matchEntire(lines[i])
            if (open == null) {
                prose.appendLine(lines[i])
                i++
                continue
            }
            val indent = open.groupValues[1].length
            val fence = open.groupValues[2]
            val closing = Regex("^ {0,3}[${fence[0]}]{${fence.length},}\\s*$")
            val body = mutableListOf<String>()
            i++
            while (i < lines.size && !closing.matches(lines[i])) {
                body += lines[i].let { line -> line.drop(line.takeWhile { it == ' ' }.length.coerceAtMost(indent)) }
                i++
            }
            i++
            flush()
            segments += MarkdownSegment.Code(body.joinToString("\n").trimEnd(), open.groupValues[3].ifBlank { null })
        }
        flush()
        return segments
    }
}
