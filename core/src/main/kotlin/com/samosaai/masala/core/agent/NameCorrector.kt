package com.samosaai.masala.core.agent

/**
 * Fixes mis-heard names in a transcript against names the phone knows (app labels, modes, skill
 * titles, wiki titles). A word or short run of words is replaced only when exactly one known name is
 * clearly the closest; ordinary words are left alone.
 */
class NameCorrector(names: Collection<String>) {
    private class Name(val display: String, val key: String)

    private val names = names.map { Name(it, key(it)) }.filter { it.key.length >= MIN_LENGTH }.distinctBy { it.key }

    fun correct(transcript: String): String {
        if (names.isEmpty()) return transcript
        val words = transcript.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val replaced = (MAX_WORDS downTo 1).firstNotNullOfOrNull { n -> replace(words, i, n) }
            if (replaced != null) {
                out += replaced.first
                i += replaced.second
            } else {
                out += words[i]
                i++
            }
        }
        return out.joinToString(" ")
    }

    private fun replace(words: List<String>, start: Int, n: Int): Pair<String, Int>? {
        if (start + n > words.size) return null
        val window = words.subList(start, start + n)
        val key = key(window.joinToString(" "))
        if (key.length < MIN_LENGTH) return null
        val scored = names.map { it to distance(key, it.key) }.sortedBy { it.second }
        val (best, bestDistance) = scored.first()
        if (bestDistance == 0 || bestDistance > allowed(best.key.length)) return null
        if (best.key.first() != key.first()) return null
        if (scored.size > 1 && scored[1].second <= bestDistance) return null
        val trailing = window.last().takeLastWhile { !it.isLetterOrDigit() }
        return best.display + trailing to n
    }

    private fun allowed(length: Int) = when {
        length < 5 -> 0
        length <= 8 -> 1
        else -> 2
    }

    private companion object {
        const val MIN_LENGTH = 4
        const val MAX_WORDS = 3

        fun key(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

        /** Damerau–Levenshtein distance (adjacent swaps count once). */
        fun distance(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
            return d[a.length][b.length]
        }
    }
}
