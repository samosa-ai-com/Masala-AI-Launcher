package com.samosaai.masala.core.files

/**
 * Turns what the model or the user typed into a full-text `MATCH` expression that can't fail to
 * parse: words only, each quoted (so `AND`, `NOT`, `-` or `"` in the text are just words), all of them
 * required, the last one a prefix ("tax retu" finds "tax return").
 */
object SearchQuery {
    private const val MAX_WORDS = 8

    /** Null when [text] has no words to search for. */
    fun match(text: String): String? {
        // Marks too: Indic vowel signs are combining marks inside a word.
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotEmpty() }.take(MAX_WORDS)
        if (words.isEmpty()) return null
        return words.mapIndexed { i, w -> if (i == words.lastIndex) "\"$w*\"" else "\"$w\"" }.joinToString(" ")
    }
}
