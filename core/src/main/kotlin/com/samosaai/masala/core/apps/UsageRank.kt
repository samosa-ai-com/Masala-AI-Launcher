package com.samosaai.masala.core.apps

import java.time.Instant
import java.time.ZoneId

/** The globe's ranges: since midnight, the last 7 days, the last 30 days. */
enum class UsageRange(val days: Long) {
    TODAY(1), WEEK(7), MONTH(30);

    /** Start of the range in epoch millis: local midnight for [TODAY], else [days] back from [now]. */
    fun since(now: Long, zone: ZoneId): Long = when (this) {
        TODAY -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        else -> now - days * 24 * 60 * 60 * 1000
    }
}

/**
 * Launch counts per app (plan UI refresh U3), from Masala's own launch log or the system's usage
 * events: count what happened in a range, rank by count with ties broken by label, keep the top N.
 */
object UsageRank {
    data class Launch(val key: String, val at: Long)
    data class Ranked(val key: String, val count: Int)

    fun counts(launches: List<Launch>, since: Long): Map<String, Int> =
        launches.asSequence().filter { it.at >= since }.groupingBy { it.key }.eachCount()

    /**
     * The [limit] most-opened of [keys] by [counts], most first; equal counts go by [label], then key, so
     * the order (and the globe) is stable. Apps never opened are left out.
     */
    fun top(counts: Map<String, Int>, keys: Collection<String>, label: (String) -> String, limit: Int = 30): List<Ranked> =
        keys.asSequence()
            .mapNotNull { k -> counts[k]?.takeIf { it > 0 }?.let { Ranked(k, it) } }
            .sortedWith(compareByDescending<Ranked> { it.count }.thenBy { label(it.key).lowercase() }.thenBy { it.key })
            .take(limit)
            .toList()

    /**
     * Apps opened at about this time of day on earlier days (the drawer's "Suggested now"): launches within
     * [windowMinutes] of [minuteOfDay], most first, ties by label.
     */
    fun suggestedNow(launches: List<Launch>, minuteOfDay: Int, zone: ZoneId, label: (String) -> String, windowMinutes: Int = 90, limit: Int = 5): List<String> {
        val near = launches.filter { l ->
            val t = Instant.ofEpochMilli(l.at).atZone(zone)
            val m = t.hour * 60 + t.minute
            val d = kotlin.math.abs(m - minuteOfDay)
            minOf(d, 24 * 60 - d) <= windowMinutes
        }
        return top(counts(near, Long.MIN_VALUE), near.map { it.key }.toSet(), label, limit).map { it.key }
    }
}
