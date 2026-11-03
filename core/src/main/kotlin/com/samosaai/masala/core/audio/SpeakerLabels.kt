package com.samosaai.masala.core.audio

/**
 * Stitches the speaker labels of consecutive transcription windows into one meeting.
 *
 * The endpoint labels each *request* on its own and always calls the first voice it hears `A`, so the
 * same person is `A` in one window and `B` in the next. Windows are uploaded with an overlap, and the
 * words in that overlap are the anchor: if the tail of the previous window has `A` saying a sentence
 * and the head of the next window has `B` saying the same words, then `B` here is `A` there.
 *
 * Pure and unit-tested; it never guesses silently — an overlap too thin or too ambiguous comes back
 * [Mapping.uncertain] and the caller says so in the UI.
 */
object SpeakerLabels {

    /** A mapping from this window's labels to the meeting's labels. */
    data class Mapping(
        val labels: Map<String, String>,
        /** True when the overlap did not decide at least one label. */
        val uncertain: Boolean,
    )

    /** How much of two turns must match before they count as the same spoken words. */
    const val MATCH_THRESHOLD = 0.5

    /**
     * Maps [incoming]'s labels (a fresh window, its clock starting at 0) onto [previous]'s
     * labels (already canonical), using the last [overlapMs] of [previous] and the first
     * [overlapMs] of [incoming]. [previousDurationMs] is the previous window's length.
     */
    fun map(
        previous: List<SpeakerTurn>,
        previousDurationMs: Long,
        incoming: List<SpeakerTurn>,
        overlapMs: Long,
    ): Mapping {
        if (overlapMs <= 0) return Mapping(emptyMap(), uncertain = true)
        val tail = previous.filter { it.speaker != null && it.endMs > previousDurationMs - overlapMs }
        val head = incoming.filter { it.speaker != null && it.startMs < overlapMs }
        if (tail.isEmpty() || head.isEmpty()) return Mapping(emptyMap(), uncertain = true)

        // local label -> canonical label -> votes
        val votes = mutableMapOf<String, MutableMap<String, Int>>()
        head.forEach { here ->
            val local = here.speaker ?: return@forEach
            tail.forEach { there ->
                val canonical = there.speaker ?: return@forEach
                if (matches(here.text, there.text)) {
                    val row = votes.getOrPut(local) { mutableMapOf() }
                    row[canonical] = (row[canonical] ?: 0) + 1
                }
            }
        }
        if (votes.isEmpty()) return Mapping(emptyMap(), uncertain = true)

        val labels = mutableMapOf<String, String>()
        var uncertain = false
        votes.forEach { (local, row) ->
            val ranked = row.entries.sortedByDescending { it.value }
            val best = ranked.first()
            val second = ranked.getOrNull(1)?.value ?: 0
            // One clear winner only: a tie would silently merge two people into one label.
            if (best.value > 0 && best.value >= second * 2) {
                labels[local] = best.key
            } else {
                uncertain = true
            }
        }
        return Mapping(labels, uncertain || labels.isEmpty())
    }

    /**
     * Assigns labels for a window with no usable overlap: this window's labels in first-appearance
     * order onto the meeting's existing labels, then fresh ones (`C`, `D`, …).
     */
    fun fallback(existing: List<String>, local: List<String>): Map<String, String> {
        val used = existing.toMutableList()
        return local.mapIndexed { index, label ->
            val target = used.getOrNull(index) ?: nextLabel(used).also { used += it }
            label to target
        }.toMap()
    }

    /** The next unused meeting label: A…Z, then `S27`, `S28`, … for a very crowded room. */
    fun nextLabel(used: Collection<String>): String {
        for (c in 'A'..'Z') {
            val label = c.toString()
            if (label !in used) return label
        }
        var n = 27
        while ("S$n" in used) n++
        return "S$n"
    }

    /**
     * Whether two turns carry the same words. Normalized word sets; a one-word turn must match
     * exactly, so "yes" and "no" do not anchor a mapping on their own.
     */
    internal fun matches(a: String, b: String): Boolean {
        val left = words(a)
        val right = words(b)
        if (left.isEmpty() || right.isEmpty()) return false
        val shared = left.count { it in right }
        if (left.size <= 1 || right.size <= 1) return shared == 1 && left.size == right.size
        val smaller = minOf(left.size, right.size)
        return shared.toDouble() / smaller >= MATCH_THRESHOLD
    }

    internal fun words(text: String): Set<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\p{M}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()
}
