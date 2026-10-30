package com.samosaai.masala.core.brief

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class BriefSpeechTest {
    @Test
    fun `labels become sentences, citations and urls go, link text stays`() {
        val md = """
            **Today**
            - 09:30 Standup (Room 4)
            - all day: Diwali

            **Weather:**
            Bengaluru: light rain, 21°C now, 27/18°C, rain 60%

            **Worth knowing**
            - Verstappen wins in Mexico ([Source](https://f1.example/a)).
            - A *new* [Android beta](https://android.example/b) is out, see https://x.example/y
            ## Extra
        """.trimIndent()
        assertEquals(
            listOf(
                "Today.",
                "09:30 Standup (Room 4).",
                "all day: Diwali.",
                "Weather.",
                "Bengaluru: light rain, 21°C now, 27/18°C, rain 60%.",
                "Worth knowing.",
                "Verstappen wins in Mexico.",
                "A new Android beta is out, see.",
                "Extra.",
            ).joinToString("\n"),
            BriefSpeech.toSpeech(md),
        )
    }

    @Test
    fun `empty or link-only text says nothing`() {
        assertEquals("", BriefSpeech.toSpeech(""))
        assertEquals("", BriefSpeech.toSpeech("https://only.example/link\n\n"))
    }
}

class BriefFallbackTest {
    private val date = LocalDate.of(2026, 10, 31)

    @Test
    fun `the fallback keeps today and the weather and says why the news is missing`() {
        val input = BriefInput(
            date, "Asia/Kolkata", "English",
            Gathered.Ok(listOf(BriefEvent("Standup", LocalTime.of(9, 30), LocalTime.of(9, 45)), BriefEvent("Diwali", null, null))),
            Gathered.Ok("Bengaluru: light rain"),
            listOf("Formula 1"),
        )
        assertEquals(
            "**Today**\n- all day: Diwali\n- 09:30–09:45 Standup\n\n**Weather**\nBengaluru: light rain\n\n" +
                "**Worth knowing**\nNews isn't available right now (the AI model or the web couldn't be reached).",
            BriefFallback.render(input, BriefFallback.reasonFor("Failed to connect to /10.0.2.2:1234")),
        )
    }

    @Test
    fun `missing parts are named`() {
        val input = BriefInput(date, "UTC", "English", Gathered.Missing("no calendar permission"), Gathered.Missing("no place set"), emptyList())
        val text = BriefFallback.render(input, BriefFallback.reasonFor("step limit"))
        assertEquals(true, "The calendar couldn't be read (no calendar permission)." in text)
        assertEquals(true, "Weather isn't available (no place set)." in text)
        assertEquals(true, text.endsWith("(the search took too many steps)."))
    }
}
