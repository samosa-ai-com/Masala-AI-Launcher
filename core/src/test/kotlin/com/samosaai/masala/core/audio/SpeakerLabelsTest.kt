package com.samosaai.masala.core.audio

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class SpeakerLabelsTest {

    /**
     * The previous window (already canonical) ends with A saying a sentence and B answering; the next
     * window labels that same A as B and that same B as A. The overlap must untangle it.
     */
    // A 60 s window with a 12 s overlap: the overlap is 48–60 s, so the new window's 0–3 s and
    // 3–6 s correspond to 48–51 s and 51–54 s here.
    private fun previous() = listOf(
        SpeakerTurn(48_000, 51_000, "A", "we should ship the smaller version on Friday"),
        SpeakerTurn(51_000, 54_000, "B", "I don't buy the timeline at all"),
    )

    private fun incoming() = listOf(
        SpeakerTurn(0, 3_000, "B", "we should ship the smaller version on Friday"),
        SpeakerTurn(3_000, 6_000, "A", "I don't buy the timeline at all"),
        SpeakerTurn(6_000, 9_000, "B", "then we need a plan B for the launch"),
    )

    @Test fun `the overlap maps a relabelled window back onto the meeting's speakers`() {
        val mapping = SpeakerLabels.map(previous(), 60_000, incoming(), overlapMs = 12_000)
        assertEquals("A", mapping.labels["B"])
        assertEquals("B", mapping.labels["A"])
        assertFalse(mapping.uncertain)
    }

    @Test fun `no overlapping speech is uncertain, not a guess`() {
        val mapping = SpeakerLabels.map(previous(), 60_000, listOf(SpeakerTurn(0, 2_000, "A", "something else entirely")), 12_000)
        assertTrue(mapping.labels.isEmpty())
        assertTrue(mapping.uncertain)
    }

    @Test fun `an overlap that matches two people equally stays uncertain`() {
        // Both people said the same words in the tail, so the incoming label has two possible homes.
        val mapping = SpeakerLabels.map(
            listOf(
                SpeakerTurn(48_000, 50_000, "A", "hello there everyone"),
                SpeakerTurn(50_000, 52_000, "B", "hello there everyone"),
            ),
            60_000,
            listOf(SpeakerTurn(0, 2_000, "C", "hello there everyone")),
            12_000,
        )
        assertTrue(mapping.uncertain)
    }

    @Test fun `fallback assigns existing labels first, then fresh letters`() {
        val map = SpeakerLabels.fallback(listOf("A", "B"), listOf("A", "B", "C"))
        assertEquals(mapOf("A" to "A", "B" to "B", "C" to "C"), map)
        assertEquals("A", SpeakerLabels.fallback(emptyList(), listOf("A"))["A"])
        assertEquals("S27", SpeakerLabels.nextLabel(('A'..'Z').map { it.toString() }))
    }

    @Test fun `the same words far from the overlap are not a match`() {
        // The text matches, but the previous turn is 20 s away from where this one must have been.
        val mapping = SpeakerLabels.map(
            listOf(SpeakerTurn(10_000, 13_000, "A", "we should ship the smaller version on Friday")),
            60_000,
            listOf(SpeakerTurn(0, 3_000, "B", "we should ship the smaller version on Friday")),
            12_000,
        )
        assertTrue(mapping.labels.isEmpty())
        assertTrue(mapping.uncertain)
    }

    @Test fun `a similar sentence with filler differences still matches`() {
        assertTrue(
            SpeakerLabels.matches(
                "Well, you should find it out by yourself.",
                "well you should find it out out by yourself",
            ),
        )
    }

    @Test fun `unrelated sentences do not match`() {
        assertFalse(SpeakerLabels.matches("let's try it out", "the budget is due next month"))
    }
}
