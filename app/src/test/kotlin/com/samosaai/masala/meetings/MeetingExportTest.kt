package com.samosaai.masala.meetings

import com.samosaai.masala.core.meeting.MeetingAction
import com.samosaai.masala.core.meeting.MeetingNote
import com.samosaai.masala.data.MeetingSessionEntity
import com.samosaai.masala.data.MeetingTurnEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingExportTest {

    private val meeting = MeetingSessionEntity(
        id = "m1",
        title = "Product Sync / Q4",
        startedAt = 1_793_600_000_000L,
        status = "ready",
        activeMs = 32 * 60_000L,
        createdAt = 0,
        updatedAt = 0,
    )

    private val turns = listOf(
        MeetingTurnEntity(meetingId = "m1", chunkIdx = 0, startMs = 0, endMs = 3_000, speaker = "A", text = "Hello."),
        MeetingTurnEntity(meetingId = "m1", chunkIdx = 0, startMs = 3_000, endMs = 6_000, speaker = "B", text = "Hi."),
    )

    private val note = MeetingNote(
        title = "Product Sync",
        summary = listOf("Shipping Friday."),
        actions = listOf(MeetingAction("Finalize testing", "Alex")),
    )

    @Test fun `a stable vault path per meeting`() {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(meeting.startedAt))
        assertEquals("notes/$date-product-sync-q4.md", MeetingExport.brainPath(meeting))
    }

    @Test fun `the markdown carries valid frontmatter and the transcript`() {
        val text = MeetingExport.markdown(meeting, note, mapOf("A" to "Priya"), turns)
        assertTrue(text.startsWith("---\ntitle: Product Sync / Q4"))
        assertTrue(text.contains("type: meeting"))
        assertTrue(text.contains("_Meeting id: m1_"))
        assertTrue(text.contains("**Speakers:** Priya, Speaker B"))
        assertTrue(text.contains("## Summary"))
        assertTrue(text.contains("## Transcript"))
        assertTrue(text.contains("- **0:00 · Priya:** Hello."))
    }

    @Test fun `sharing leaves the transcript out`() {
        val text = MeetingExport.markdown(meeting, note, mapOf("A" to "Priya"), turns, transcript = false)
        assertTrue(text.contains("## Summary"))
        assertTrue(!text.contains("## Transcript"))
    }

    @Test fun `the Second Brain accepts the note as a meeting record`() {
        val root = java.nio.file.Files.createTempDirectory("brain").toFile()
        val store = com.samosaai.masala.secondbrain.SecondBrainStore(root) { "2026-11-03" }
        store.ensureDefaults()
        val text = MeetingExport.markdown(meeting, note, mapOf("A" to "Priya"), turns)
        val outcome = store.writeRecord("notes/2026-11-03-product-sync.md", text, append = false, allowDuplicate = true)
        assertTrue(outcome.errors.joinToString("; "), outcome.ok)
        val saved = java.io.File(root, "notes/2026-11-03-product-sync.md").readText()
        assertTrue(saved.contains("Priya"))
        root.deleteRecursively()
    }

    @Test fun `a title that is only punctuation still makes a path`() {
        assertEquals("meeting", MeetingExport.slug("!!! ???"))
        assertTrue(MeetingExport.slug("A".repeat(200)).length <= 60)
    }
}
