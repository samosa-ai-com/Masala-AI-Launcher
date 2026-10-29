package com.samosaai.masala.core.agent

import com.samosaai.masala.core.contract.MasalaAction

/**
 * Commands simple enough to skip the model: timers, alarms and opening an app. Anything with extra
 * words in it ("timer for the pasta") returns null and goes to the agent instead. English only.
 */
object CommandFastPath {
    private val duration = Regex("""(\d+(?:\.\d+)?)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""")
    private val clock = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?(?=\s|$)""")
    private val open = Regex("""^(?:please\s+)?(?:open|launch|start)\s+(.+)$""")
    private val filler = setOf(
        "set", "start", "create", "make", "a", "an", "the", "for", "of", "please", "me", "and", "in", "at",
        "up", "wake", "my", "new", "to", "on", "hey", "masala", "timer", "alarm", "from", "now",
    )

    /** [resolveApp] turns the words after "open" into a launch action, or null if no app matches. */
    fun match(text: String, resolveApp: (String) -> MasalaAction.LaunchApp?): MasalaAction? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        // "start a timer" also looks like "start <app>"; only an app that resolves counts.
        open.matchEntire(t)?.let { m -> resolveApp(m.groupValues[1].trim())?.let { return it } }
        val words = t.split(' ')
        return when {
            "timer" in words -> timer(t)
            "alarm" in words || t.contains("wake me up") -> alarm(t)
            else -> null
        }
    }

    private fun leftover(text: String) = text.split(' ').filter { it.isNotEmpty() && it !in filler }

    private fun timer(t: String): MasalaAction? {
        val matches = duration.findAll(t).toList()
        if (matches.isEmpty()) return null
        if (leftover(duration.replace(t, " ")).isNotEmpty()) return null
        val seconds = matches.sumOf { m ->
            val n = m.groupValues[1].toDouble()
            when (m.groupValues[2].first()) {
                'h' -> n * 3600
                'm' -> n * 60
                else -> n
            }
        }.toInt()
        return if (seconds > 0) MasalaAction.StartTimer(seconds) else null
    }

    private fun alarm(t: String): MasalaAction? {
        val m = clock.findAll(t).singleOrNull() ?: return null
        if (leftover(clock.replace(t, " ")).isNotEmpty()) return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val meridiem = m.groupValues[3].firstOrNull()
        if (minute > 59) return null
        if (meridiem != null) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (meridiem == 'p') 12 else 0
        } else if (hour > 23) {
            return null
        }
        return MasalaAction.SetAlarm(hour, minute)
    }
}
