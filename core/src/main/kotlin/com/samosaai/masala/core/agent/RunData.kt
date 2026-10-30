package com.samosaai.masala.core.agent

/**
 * Data the app gathers for a skill run before the model starts (the daily brief's calendar, weather and
 * topics). It goes in front of the run's first message between [OPEN] and [CLOSE], so it's saved with
 * the transcript and survives a reopened run, while the sheet shows only what the user typed.
 */
object RunData {
    const val OPEN = "<run-data>"
    const val CLOSE = "</run-data>"

    /** [data] then [message]. Markers inside [data] are removed, so a calendar title can't end the block early. */
    fun prepend(data: String, message: String): String {
        val clean = data.replace(OPEN, "").replace(CLOSE, "").trim()
        return "$OPEN\n$clean\n$CLOSE\n\n$message"
    }

    /** What the user wrote, without a leading data block; the text as is when there is none. */
    fun strip(text: String): String {
        if (!text.startsWith(OPEN)) return text
        val end = text.indexOf(CLOSE)
        return if (end < 0) "" else text.substring(end + CLOSE.length).trimStart()
    }
}
