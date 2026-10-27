package com.samosaai.masala.core.apps

import java.text.Normalizer
import java.util.Locale

/**
 * Drawer search. The idea comes from mLauncher's FuzzyFinder and Olauncher's filter (both GPL, so
 * nothing is copied): normalize away case, accents and separators, prefer a match at the start of the
 * label, then at the start of a word, then anywhere, then a fuzzy match of the letters in order.
 */
object AppSearch {
    /** How a label matched. Higher [tier] wins, then higher [score], then earlier [start]. */
    data class Match(val tier: Int, val score: Double, val start: Int)

    const val PREFIX = 3
    const val WORD_PREFIX = 2
    const val SUBSTRING = 1
    const val FUZZY = 0

    /** Fuzzy matches below this share of the best possible score are dropped. */
    private const val FUZZY_THRESHOLD = 0.5

    private val combiningMarks = Regex("""\p{Mn}+""")
    private val separators = Regex("""[\s\-_.,:;'’"/()&+]+""")

    /** Lower case, no accents ("Café" → "cafe"), separators collapsed to one space. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .replace(separators, " ")
            .trim()

    fun match(query: String, label: String): Match? {
        val q = normalize(query)
        val l = normalize(label)
        if (q.isEmpty() || l.isEmpty()) return null
        if (l.startsWith(q)) return Match(PREFIX, 1.0, 0)
        val wordStart = Regex("""(?<=^| )${Regex.escape(q)}""").find(l)
        if (wordStart != null) return Match(WORD_PREFIX, 1.0, wordStart.range.first)
        val at = l.indexOf(q)
        if (at >= 0) return Match(SUBSTRING, 1.0, at)
        return fuzzy(q.replace(" ", ""), l)
    }

    /**
     * The query's letters in order, anywhere in the label: one point per letter, two more when it
     * follows the previous letter directly, two more at the start of a word ("gm" → "Google Maps").
     * Tries both the earliest letters and word starts first, and keeps the better one.
     */
    private fun fuzzy(q: String, l: String): Match? {
        if (q.isEmpty()) return null
        val best = listOfNotNull(align(q, l, preferWordStarts = false), align(q, l, preferWordStarts = true))
            .maxByOrNull { it.score } ?: return null
        return best.takeIf { it.score >= FUZZY_THRESHOLD }
    }

    private fun align(q: String, l: String, preferWordStarts: Boolean): Match? {
        var points = 0
        var pos = 0
        var previous = -2
        var start = -1
        for (c in q) {
            val next = (if (preferWordStarts) nextWordStart(l, c, pos) else -1).takeIf { it >= 0 } ?: l.indexOf(c, pos)
            if (next < 0) return null
            points += 1
            if (next == previous + 1) points += 2
            if (isWordStart(l, next)) points += 2
            if (start < 0) start = next
            previous = next
            pos = next + 1
        }
        // The first letter can't follow another, so it scores at most 3.
        return Match(FUZZY, points / (5.0 * q.length - 2), start)
    }

    private fun isWordStart(l: String, i: Int) = i == 0 || l[i - 1] == ' '

    private fun nextWordStart(l: String, c: Char, from: Int): Int {
        var i = l.indexOf(c, from)
        while (i >= 0 && !isWordStart(l, i)) i = l.indexOf(c, i + 1)
        return i
    }

    /**
     * Entries matching [query], best first. An entry matches on its shown label or, if renamed, its
     * original one. Ties keep the input order (alphabetical from the catalog).
     */
    fun <T> rank(query: String, entries: List<T>, labels: (T) -> List<String>): List<T> =
        entries.withIndex()
            .mapNotNull { (i, entry) ->
                labels(entry).mapNotNull { match(query, it) }
                    .maxWithOrNull(matchOrder)
                    ?.let { Triple(entry, it, i) }
            }
            .sortedWith(compareByDescending<Triple<T, Match, Int>, Match>(matchOrder) { it.second }.thenBy { it.third })
            .map { it.first }

    private val matchOrder: Comparator<Match> =
        compareBy<Match> { it.tier }.thenBy { it.score }.thenByDescending { it.start }

    /**
     * The app to open as soon as typing leaves one result (Olauncher §2.4), or null. Never while the
     * keyboard is composing (Chinese, Japanese and Korean keyboards build each character over several
     * key presses, so the half-typed text would open the wrong app), and never for a query starting
     * with a space, which is how the user says "don't open anything yet".
     */
    fun <T> autoLaunchTarget(query: String, composing: Boolean, results: List<T>): T? =
        results.singleOrNull()?.takeIf { !composing && query.isNotBlank() && !query.startsWith(" ") }
}
