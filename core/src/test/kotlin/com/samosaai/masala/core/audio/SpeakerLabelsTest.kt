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
    private fun previous() = listOf(
        SpeakerTurn(60_000, 63_000, "A", "we should ship the smaller version on Friday"),
        SpeakerTurn(63_000, 66_000, "B", "I don't buy the timeline at all"),
    )

    private fun incoming() = listOf(
        SpeakerTurn(0, 3_000, "B", "we should ship the smaller version on Friday"),
        SpeakerTurn(3_000, 6_000, "A", "I don't buy the timeline at all"),
        SpeakerTurn(6_000, 9_000, "B", "then we need a plan B for the launch"),
    )

    @Test fun `the overlap maps a relabelled window back onto the meeting's speakers`() {
        val mapping = SpeakerLabels.map(previous(), 66_000, incoming(), overlapMs = 12_000)
        assertEquals("A", mapping.labels["B"])
        assertEquals("B", mapping.labels["A"])
        assertFalse(mapping.uncertain)
    }

    @Test fun `no overlapping speech is uncertain, not a guess`() {
        val mapping = SpeakerLabels.map(previous(), 66_000, listOf(SpeakerTurn(0, 2_000, "A", "something else entirely")), 12_000)
        assertTrue(mapping.labels.isEmpty())
        assertTrue(mapping.uncertain)
    }

    @Test fun `an overlap that matches two people equally stays uncertain`() {
        // Both people said the same words in the tail, so the incoming label has two possible homes.
        val mapping = SpeakerLabels.map(
            listOf(
                SpeakerTurn(60_000, 62_000, "A", "hello there everyone"),
                SpeakerTurn(62_000, 64_000, "B", "hello there everyone"),
            ),
            66_000,
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
