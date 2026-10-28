package com.samosaai.masala.layout

import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.apps.ProfileKind
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.NextEvent
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GenerateInputTest {
    private val now = LocalDateTime.of(2026, 10, 28, 9, 0, 0)

    private fun app(label: String) = AppEntry(
        AppKey("com.example.${label.lowercase()}", "com.example.${label.lowercase()}.Main", 0),
        label, label, ProfileKind.PERSONAL, Visibility.DEFAULT, false
    )

    @Test
    fun `request carries apps, layout, calendar and skills`() {
        val request = buildGenerateRequest(
            apps = listOf(app("Phone")),
            layout = null,
            snapshot = ContextSnapshot(now = now, nextEvent = NextEvent("Standup", now.plusHours(2))),
            skills = listOf(MasalaSkill(id = "meeting_notes", title = "", instructions = "x")),
            userText = "morning screen",
        )
        assertEquals("morning screen", request.userText)
        assertEquals(listOf("Phone"), request.context.apps.map { it.label })
        assertEquals("Next: Standup at 11:00\nLeave by 10:30", request.context.calendar)
        assertEquals(listOf(PromptSkill("meeting_notes", "meeting_notes")), request.context.skills)
        assertNull(request.imageBase64)
        assertNull(request.temperature)
    }

    @Test
    fun `image format is passed through to the request`() {
        val req = buildGenerateRequest(
            apps = emptyList(),
            layout = null,
            snapshot = ContextSnapshot(now = now),
            skills = emptyList(),
            userText = "x",
            imageBase64 = "abc",
            imageFormat = "jpeg",
        )
        assertEquals("jpeg", req.imageFormat)
        assertEquals("abc", req.imageBase64)
    }

    @Test
    fun `skill titles fall back to ids only when blank`() {
        val request = buildGenerateRequest(
            apps = emptyList(),
            layout = null,
            snapshot = ContextSnapshot(now = now),
            skills = listOf(MasalaSkill(id = "chef", title = "Chef", instructions = "x")),
            userText = "x",
            imageBase64 = "aGVsbG8=",
            temperature = 0.2f,
        )
        assertEquals(listOf(PromptSkill("chef", "Chef")), request.context.skills)
        assertEquals("aGVsbG8=", request.imageBase64)
        assertEquals(0.2f, request.temperature)
        assertNull(request.context.calendar)
    }
}
