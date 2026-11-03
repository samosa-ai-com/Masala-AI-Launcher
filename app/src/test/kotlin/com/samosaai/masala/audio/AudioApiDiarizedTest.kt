package com.samosaai.masala.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Captured `/asr/transcribe` answers, so the wire shape is pinned without a network call. */
class AudioApiDiarizedTest {

    private val real = """
        {"sessionId":"voyager/transcribe/X","transcript":"I heard that… speaker_B|> Wow.",
         "audioDurationMs":13440,
         "turns":[
           {"turnId":0,"startMs":0,"endMs":3660,"transcript":"I heard that the multi-speaker audio sounds amazing.","speaker":"A"},
           {"turnId":1,"startMs":3820,"endMs":6140,"transcript":"Oh, what's so good about it?","speaker":"B"},
           {"turnId":2,"startMs":6300,"endMs":6380,"transcript":"speaker_B|>"},
           {"turnId":3,"startMs":6380,"endMs":7260,"transcript":"Wow."},
           {"turnId":4,"startMs":7500,"endMs":7820,"transcript":"Well, what?","speaker":"B"}
         ]}
    """.trimIndent()

    @Test fun `turns, speakers and times are read`() {
        val transcript = AudioApi.parseDiarized(real)
        assertEquals(13_440L, transcript.durationMs)
        assertEquals(listOf("A", "B"), transcript.speakers)
        // Five turns in the answer, one of them the speaker artefact.
        assertEquals(4, transcript.turns.size)
        assertEquals(3_660L, transcript.turns.first().endMs)
    }

    @Test fun `the speaker artefact turn is dropped, not shown as speech`() {
        val transcript = AudioApi.parseDiarized(real)
        assertTrue(transcript.turns.none { it.text.contains("|>") })
    }

    @Test fun `a response without turns becomes one unlabelled turn`() {
        val flat = AudioApi.parseDiarized("""{"transcript":"just text","audioDurationMs":2000}""")
        assertEquals(1, flat.turns.size)
        assertNull(flat.turns.single().speaker)
        assertEquals("just text", flat.turns.single().text)
    }

    @Test fun `an empty response has no turns`() {
        assertEquals(0, AudioApi.parseDiarized("""{"transcript":""}""").turns.size)
    }

    @Test fun `a missing end time falls back to the start`() {
        val transcript = AudioApi.parseDiarized("""{"turns":[{"startMs":1000,"transcript":"hi","speaker":"A"}]}""")
        assertEquals(1_000L, transcript.turns.single().endMs)
    }
}
