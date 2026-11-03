package com.samosaai.masala.ui.components

/**
 * The Markdown the chat shows: top-level fenced code blocks are split out first, since the renderer
 * draws code without a copy button; everything else is Markdown for the renderer (see `Markdown.kt`).
 */
sealed interface MarkdownSegment {
    data class Prose(val markdown: String) : MarkdownSegment
    data class Code(val code: String, val language: String?) : MarkdownSegment
    data class Table(
        val headers: List<String>,
        val aligns: List<TableAlign>,
        val rows: List<List<String>>,
    ) : MarkdownSegment
}

/** A column's alignment, parsed from the delimiter row (`:---`, `:--:`, `---:`). */
enum class TableAlign { LEFT, CENTER, RIGHT }

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

    /**
     * Splits GFM tables out of [prose]: a pipe header row, a delimiter row of dashes with optional
     * edge colons, then pipe body rows. The bundled Markdown renderer sizes every column equally,
     * which squeezes wide tables into unreadable slivers on a phone, so tables are drawn in a
     * sideways-scrollable grid whose columns size to their content instead.
     */
    fun splitTables(prose: String): List<MarkdownSegment> {
        val segments = mutableListOf<MarkdownSegment>()
        val text = StringBuilder()
        fun flush() {
            val t = text.toString().trim('\n')
            if (t.isNotBlank()) segments += MarkdownSegment.Prose(t)
            text.setLength(0)
        }
        val lines = prose.lines()
        var i = 0
        while (i < lines.size) {
            val header = tableLine(lines.getOrNull(i))
            val delimiter = tableLine(lines.getOrNull(i + 1))
            if (header != null && delimiter != null && '|' in header && isDelimiterRow(delimiter) &&
                splitRow(header).size == splitRow(delimiter).size
            ) {
                val headers = splitRow(header)
                val aligns = splitRow(delimiter).map(::alignOf)
                val width = headers.size
                val rows = mutableListOf<List<String>>()
                var j = i + 2
                while (j < lines.size) {
                    val row = tableLine(lines[j]) ?: break
                    if ('|' !in row || isDelimiterRow(row)) break
                    rows += splitRow(row).fitTo(width, "")
                    j++
                }
                flush()
                segments += MarkdownSegment.Table(headers, aligns.fitTo(width, TableAlign.LEFT), rows)
                i = j
            } else {
                text.appendLine(lines.getOrNull(i).orEmpty())
                i++
            }
        }
        flush()
        return segments
    }

    /** A line a table may use: visual indent under 4 (deeper indents are code), and never a quote. */
    private fun tableLine(line: String?): String? {
        if (line == null) return null
        val stripped = line.trimStart()
        if (stripped.startsWith('>')) return null
        if (visualIndent(line) >= 4) return null
        return stripped
    }

    /** Visual indent in columns: spaces count 1, tabs advance to the next multiple of 4. */
    private fun visualIndent(line: String): Int {
        var column = 0
        for (ch in line) {
            when (ch) {
                ' ' -> column++
                '\t' -> column = (column / 4 + 1) * 4
                else -> break
            }
        }
        return column
    }

    /** A delimiter cell: dashes with optional edge colons. */
    private val DELIMITER_CELL = Regex(":?-{1,}:?")

    /** A delimiter row: pipes of dashes with optional edge colons. */
    private fun isDelimiterRow(line: String): Boolean {
        val cells = splitRow(line)
        if (cells.isEmpty()) return false
        return cells.all { it.matches(DELIMITER_CELL) }
    }

    /**
     * Splits one row on unescaped pipes. Escapes pass through untouched for the cell renderer:
     * `\|` renders as `|`, `\\` as `\`. Outer border pipes count as a border only when both edges
     * really have them; otherwise edge empties are real cells.
     */
    private fun splitRow(line: String): List<String> {
        val s = line.trim()
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        s.forEachIndexed { index, ch ->
            if (ch == '|' && !isEscaped(s, index)) {
                cells += cur.toString().trim()
                cur.clear()
            } else {
                cur.append(ch)
            }
        }
        cells += cur.toString().trim()
        if (cells.size >= 2 && s.startsWith('|') && s.endsWith('|') && !isEscaped(s, s.length - 1)) {
            return cells.drop(1).dropLast(1)
        }
        return cells
    }

    /** Whether the char at [index] is escaped by an odd run of backslashes. */
    private fun isEscaped(s: String, index: Int): Boolean {
        var backslashes = 0
        var k = index - 1
        while (k >= 0 && s[k] == '\\') {
            backslashes++
            k--
        }
        return backslashes % 2 == 1
    }

    private fun alignOf(cell: String): TableAlign = when {
        cell.startsWith(':') && cell.endsWith(':') && cell.length > 1 -> TableAlign.CENTER
        cell.endsWith(':') -> TableAlign.RIGHT
        else -> TableAlign.LEFT
    }

    private fun <T> List<T>.fitTo(width: Int, pad: T): List<T> {
        val taken = take(width)
        return if (taken.size >= width) taken else taken + List(width - taken.size) { pad }
    }
}
