package com.samosaai.masala.brief

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Rules the skill widget uses for scheduled skills like the daily brief (plan `phase5-daily-brief.md` B5). */
object BriefWidget {
    /**
     * Whether a finished run is read aloud without a tap (plan D5-B): once per run, a successful run from
     * today, before noon, with read-aloud on and the phone not silenced or in Do Not Disturb.
     */
    fun autoReadDue(
        runId: Long,
        status: String,
        finishedAt: Long?,
        now: Long,
        zone: ZoneId,
        readAloud: Boolean,
        quiet: Boolean,
        lastReadRunId: Long,
    ): Boolean {
        if (!readAloud || quiet || status != "done" || finishedAt == null || runId == lastReadRunId) return false
        val nowAt = Instant.ofEpochMilli(now).atZone(zone)
        return Instant.ofEpochMilli(finishedAt).atZone(zone).toLocalDate() == nowAt.toLocalDate() && nowAt.toLocalTime() < LocalTime.NOON
    }

    /** "Today 07:31", "Yesterday 07:31", or "Thu 29 Oct" for older runs, so an old brief isn't taken for today's. */
    fun runLabel(millis: Long, todayWord: String, yesterdayWord: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)
        return when (ChronoUnit.DAYS.between(at.toLocalDate(), today)) {
            0L -> "$todayWord $time"
            1L -> "$yesterdayWord $time"
            else -> DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(at)
        }
    }

    /** What a medium widget shows: the first block (the brief's Today section). */
    fun preview(markdown: String): String =
        markdown.trim().split(Regex("\\n\\s*\\n")).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}
