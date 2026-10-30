package com.samosaai.masala.core.brief

/** The brief as it is read aloud (plan `phase5-daily-brief.md` B4): no links, no Markdown, labels as sentences. */
object BriefSpeech {
    /** "([Source](url))": a citation, dropped whole. */
    private val CITATION = Regex("\\(\\s*\\[[^\\]]*]\\([^)]*\\)\\s*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val BARE_URL = Regex("https?://\\S+")
    private val LABEL = Regex("^\\*\\*([^*]+?):?\\*\\*:?$")

    fun toSpeech(markdown: String): String = markdown.lines().mapNotNull { raw ->
        var line = raw.trim()
        LABEL.matchEntire(line)?.let { return@mapNotNull sentence(it.groupValues[1]) }
        line = line.removePrefix("#").trimStart('#').trim()
        if (line.startsWith("- ") || line.startsWith("* ")) line = line.drop(2)
        line = line.replace(CITATION, "").replace(LINK, "$1").replace(BARE_URL, "")
        line = line.replace("**", "").replace("__", "").replace("`", "").replace(Regex("(?<!\\w)[*_](?!\\s)|(?<!\\s)[*_](?!\\w)"), "")
        line = line.replace(Regex("\\s+"), " ").replace(" .", ".").replace(" ,", ",").trim()
        if (line.isEmpty()) null else sentence(line)
    }.joinToString("\n")

    private fun sentence(text: String): String {
        val t = text.trim().trimEnd(':').trim()
        return if (t.isEmpty() || t.last() in ".!?") t else "$t."
    }
}

/**
 * The brief written by code when the model or the web failed (plan B4): Today and Weather from the
 * gathered data, and why the news is missing. So a scheduled brief always lands on the widget.
 */
object BriefFallback {
    fun render(input: BriefInput, reason: String): String = buildString {
        appendLine("**Today**")
        when (val events = input.events) {
            is Gathered.Missing -> appendLine("The calendar couldn't be read (${events.reason}).")
            is Gathered.Ok -> if (events.value.isEmpty()) appendLine("Nothing on the calendar.") else
                BriefInput.ordered(events.value).forEach { appendLine("- " + it.line()) }
        }
        appendLine()
        appendLine("**Weather**")
        when (val weather = input.weather) {
            is Gathered.Missing -> appendLine("Weather isn't available (${weather.reason}).")
            is Gathered.Ok -> appendLine(oneLine(weather.value))
        }
        appendLine()
        appendLine("**Worth knowing**")
        append("News isn't available right now ($reason).")
    }

    /** A short reason for the user from the run's error; the full error stays in the run and the log. */
    fun reasonFor(error: String): String = when {
        error.contains("step limit", ignoreCase = true) -> "the search took too many steps"
        else -> "the AI model or the web couldn't be reached"
    }
}
