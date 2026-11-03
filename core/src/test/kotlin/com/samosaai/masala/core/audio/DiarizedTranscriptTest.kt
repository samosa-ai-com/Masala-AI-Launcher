package com.samosaai.masala.core.audio

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class DiarizedTranscriptTest {

    private val turns = listOf(
        SpeakerTurn(0, 3_000, "A", "Hello there."),
        SpeakerTurn(3_000, 5_000, "B", "Hi."),
        SpeakerTurn(5_000, 65_000, "A", "Let's start."),
    )

    @Test fun `turns carry a clock, speaker and text`() {
        assertEquals("[0:00] A: Hello there.", turns.let { DiarizedTranscript(it) }.numbered().lines()[0])
        assertEquals("[0:05] A: Let's start.", DiarizedTranscript(turns).numbered().lines()[2])
    }

    @Test fun `past an hour the clock grows an hours field`() {
        assertEquals("1:02:03", DiarizedTranscript.clockOf(3_723_000))
        assertEquals("0:05", DiarizedTranscript.clockOf(5_400))
    }

    @Test fun `a turn without a speaker takes the previous one`() {
        val mixed = listOf(
            SpeakerTurn(0, 1_000, "A", "first"),
            SpeakerTurn(1_000, 2_000, "B", "then B"),
            SpeakerTurn(2_000, 3_000, null, "still B"),
        )
        val filled = DiarizedTranscript(mixed).withInheritedSpeakers()
        assertEquals(listOf("A", "B", "B"), filled.turns.map { it.speaker })
    }

    @Test fun `a leading unlabelled turn takes the next known speaker`() {
        val filled = DiarizedTranscript(listOf(
            SpeakerTurn(0, 1_000, null, "hello"),
            SpeakerTurn(1_000, 2_000, "B", "hi"),
        )).withInheritedSpeakers()
        assertEquals(listOf("B", "B"), filled.turns.map { it.speaker })
    }

    @Test fun `speakers are listed once, in first-heard order`() {
        assertEquals(listOf("A", "B"), DiarizedTranscript(turns).speakers)
    }

    @Test fun `an overlap is dropped from the front`() {
        val trimmed = DiarizedTranscript(turns).withoutOverlap(4_000)
        // The turn ending at 5 s is still (partly) past the overlap; the one ending at 3 s is not.
        assertEquals(listOf(3_000L, 5_000L), trimmed.turns.map { it.startMs })
    }

    @Test fun `shifted moves the whole window onto the meeting clock`() {
        val shifted = DiarizedTranscript(listOf(SpeakerTurn(0, 1_000, "A", "x"))).shifted(300_000)
        assertEquals(300_000L, shifted.turns.single().startMs)
        assertEquals(301_000L, shifted.turns.single().endMs)
    }

    @Test fun `markdown names the speaker and the time`() {
        assertTrue(DiarizedTranscript(turns).toMarkdown().startsWith("- **0:00 · A:** Hello there."))
    }

    @Test fun `plain text keeps every word and no labels`() {
        assertEquals("Hello there. Hi. Let's start.", DiarizedTranscript(turns).plainText())
    }
}
