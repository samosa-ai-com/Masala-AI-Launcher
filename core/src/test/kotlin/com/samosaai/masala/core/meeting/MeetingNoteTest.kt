package com.samosaai.masala.core.meeting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeetingNoteTest {

    private val note = MeetingNote(
        title = "Product Sync",
        summary = listOf("Shipping the smaller version on Friday."),
        keyPoints = listOf("Feature A is on track."),
        decisions = listOf("Ship the smaller version."),
        actions = listOf(
            MeetingAction("Finalize testing for Feature A", owner = "Alex"),
            MeetingAction("Share updated designs", owner = "Priya", due = "Thursday"),
        ),
        questions = listOf("Who owns the launch plan?"),
        followUps = listOf("Send the notes to the team."),
        template = "general",
        windows = 3,
    )

    @Test fun `markdown has every section in a stable shape`() {
        val markdown = note.toMarkdown()
        assertTrue(markdown.startsWith("# Product Sync"))
        assertTrue(markdown.contains("## Summary\n- Shipping the smaller version on Friday."))
        assertTrue(markdown.contains("## Key points"))
        assertTrue(markdown.contains("## Decisions"))
        assertTrue(markdown.contains("## Action items\n- [ ] Finalize testing for Feature A — Alex"))
        assertTrue(markdown.contains("- [ ] Share updated designs — Priya (by Thursday)"))
        assertTrue(markdown.contains("## Open questions"))
        assertTrue(markdown.contains("## Follow-ups"))
    }

    @Test fun `a failed window is named, not hidden`() {
        val withGap = note.copy(failedWindows = listOf("12:00–24:00"))
        assertTrue(withGap.toMarkdown().contains("These parts could not be summarised: 12:00–24:00"))
    }

    @Test fun `an empty note is recognised`() {
        assertTrue(MeetingNote().isEmpty)
        assertEquals("", MeetingNote().toMarkdown().replace("# Meeting notes", "").trim())
    }

    @Test fun `templates resolve by id with a safe default`() {
        assertEquals(MeetingTemplate.OneOnOne, MeetingTemplate.byId("one_on_one"))
        assertEquals(MeetingTemplate.General, MeetingTemplate.byId("nope"))
        assertEquals(MeetingTemplate.General, MeetingTemplate.byId(null))
        assertTrue(MeetingTemplate.Standup.sections.contains("actions"))
    }
}
