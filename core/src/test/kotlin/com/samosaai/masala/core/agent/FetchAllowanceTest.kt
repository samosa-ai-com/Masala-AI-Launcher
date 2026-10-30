package com.samosaai.masala.core.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchAllowanceTest {
    private val search = """
        Search results for 'Formula 1 news October 2026' (top 2):

        1. Verstappen wins in Mexico
           https://www.f1.example/news/mexico-gp?utm_source=ddg&id=7
           Snippet.
        2. Calendar change
           http://motorsport.example/2026/calendar/.
    """.trimIndent()

    @Test
    fun `a url from a search result may be opened, written a little differently`() {
        assertTrue(FetchAllowance.allows("https://www.f1.example/news/mexico-gp?utm_source=ddg&id=7", listOf(search)))
        assertTrue(FetchAllowance.allows("https://F1.example/news/mexico-gp/?id=7#top", listOf(search)))
        assertTrue(FetchAllowance.allows("https://motorsport.example/2026/calendar", listOf(search)))
    }

    @Test
    fun `a url no tool returned is refused`() {
        assertFalse(FetchAllowance.allows("https://evil.example/?q=Standup%20Room%204", listOf(search)))
        assertFalse(FetchAllowance.allows("https://www.f1.example/news/mexico-gp?id=8", listOf(search)))
        assertFalse(FetchAllowance.allows("https://www.f1.example/news", listOf(search)))
        assertFalse(FetchAllowance.allows("https://www.f1.example/news/mexico-gp?id=7", emptyList()))
    }

    @Test
    fun `odd addresses are refused`() {
        assertFalse(FetchAllowance.allows("file:///sdcard/notes.txt", listOf("file:///sdcard/notes.txt")))
        assertFalse(FetchAllowance.allows("not a url", listOf(search)))
        assertFalse(FetchAllowance.allows("https://", listOf(search)))
    }
}
