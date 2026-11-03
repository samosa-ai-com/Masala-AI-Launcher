package com.samosaai.masala.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class MeetingTimelineTest {

    private fun turn(start: Long, end: Long, text: String = "x") = SpeakerTurn(start, end, "A", text)

    @Test fun `a window is placed on the meeting clock after its overlap`() {
        val placed = MeetingTimeline.place(
            listOf(turn(20_000, 23_000)),
            chunkStartMs = 300_000,
            overlapMs = 12_000,
        )
        // 20 s into the upload, which began 12 s before the chunk: 300 - 12 + 20 = 308 s.
        assertEquals(308_000L, placed.single().startMs)
        assertEquals(311_000L, placed.single().endMs)
    }

    @Test fun `turns that only exist in the overlap are dropped`() {
        val placed = MeetingTimeline.place(
            listOf(turn(0, 8_000), turn(8_000, 13_000), turn(15_000, 18_000)),
            300_000,
            12_000,
        )
        assertEquals(2, placed.size)
        // The crossing turn is clamped to the chunk start rather than reaching into the previous one.
        assertEquals(300_000L, placed.first().startMs)
        assertEquals(303_000L, placed.last().startMs)
    }

    @Test fun `the first window has no overlap to drop`() {
        val placed = MeetingTimeline.place(listOf(turn(0, 3_000), turn(3_000, 5_000)), 0, 0)
        assertEquals(listOf(0L, 3_000L), placed.map { it.startMs })
    }

    @Test fun `an empty window is empty`() {
        assertEquals(0, MeetingTimeline.place(emptyList(), 100_000, 12_000).size)
    }
}
