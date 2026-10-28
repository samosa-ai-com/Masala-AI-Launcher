package com.samosaai.masala.ui.components

/**
 * The Markdown the chat shows, parsed without Android types: fenced code blocks are split out first
 * (they get a copy button), the rest becomes blocks of inline spans. Covers what models write in chat:
 * headings, lists, quotes, bold, italic, inline code and links. Tables and images stay plain text.
 */
sealed interface MarkdownSegment {
    data class Prose(val markdown: String) : MarkdownSegment
    data class Code(val code: String, val language: String?) : MarkdownSegment
}

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Bullet(val indent: Int, val spans: List<MdSpan>) : MdBlock
    data class Numbered(val number: String, val spans: List<MdSpan>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
}

data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

object MarkdownParser {
    private val openingFence = Regex("^( {0,3})(`{3,}|~{3,})\\s*([^\\s`]*)[^`]*$")
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
    private val quote = Regex("^>\\s?(.*)$")
    private val inline = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\*([^*\s][^*]*?)\*|(?<![\w])_([^_\s][^_]*?)_(?![\w])|\[([^\]]+)]\(([^)]*)\)""")

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

    fun blocks(prose: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(spans(paragraph.joinToString(" ")))
            paragraph.clear()
        }
        for (line in prose.lines()) {
            val h = heading.matchEntire(line)
            val b = bullet.matchEntire(line)
            val n = numbered.matchEntire(line)
            val q = quote.matchEntire(line)
            when {
                line.isBlank() -> flush()
                h != null -> { flush(); blocks += MdBlock.Heading(h.groupValues[1].length, spans(h.groupValues[2].trim())) }
                b != null -> { flush(); blocks += MdBlock.Bullet(b.groupValues[1].length / 2, spans(b.groupValues[2])) }
                n != null -> { flush(); blocks += MdBlock.Numbered(n.groupValues[1], spans(n.groupValues[2])) }
                q != null -> { flush(); blocks += MdBlock.Quote(spans(q.groupValues[1])) }
                else -> paragraph += line.trim()
            }
        }
        flush()
        return blocks
    }

    fun spans(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        var last = 0
        for (m in inline.findAll(text)) {
            if (m.range.first > last) out += MdSpan(text.substring(last, m.range.first))
            val g = m.groups
            out += when {
                g[1] != null -> MdSpan(g[1]!!.value, bold = true)
                g[2] != null -> MdSpan(g[2]!!.value, code = true)
                g[3] != null -> MdSpan(g[3]!!.value, italic = true)
                g[4] != null -> MdSpan(g[4]!!.value, italic = true)
                else -> MdSpan(g[5]!!.value)
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += MdSpan(text.substring(last))
        return out
    }
}
