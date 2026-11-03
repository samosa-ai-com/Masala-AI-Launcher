package com.samosaai.masala.agent

import com.samosaai.masala.i18n.Language
import com.samosaai.masala.llm.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptCleanerTest {
    private fun cleaner(timeoutMs: Long = 1_000, reply: suspend (List<ChatMessage>) -> String) =
        LlmTranscriptCleaner(reply, { Language.GERMAN }, timeoutMs)

    @Test fun theModelsCorrectionIsUsed() = runBlocking {
        var sent: List<ChatMessage> = emptyList()
        val out = cleaner { sent = it; "Wie wird das Wetter heute?" }.clean("wie wird das wetter heute")
        assertEquals("Wie wird das Wetter heute?", out)
        // The dictation is wrapped, and the language is named so the model never translates.
        assertEquals("<dictation>\nwie wird das wetter heute\n</dictation>", sent.last().textContent)
        assertTrue(sent.first().textContent.contains("German"))
    }

    @Test fun aFailureOrTimeoutSendsTheRawText() = runBlocking {
        assertEquals("set a timer", cleaner { error("401") }.clean("set a timer"))
        assertEquals("set a timer", cleaner(timeoutMs = 50) { delay(5_000); "Set a timer." }.clean("set a timer"))
    }

    @Test fun blankTextSkipsTheModel() = runBlocking {
        var called = false
        assertEquals("  ", cleaner { called = true; "x" }.clean("  "))
        assertTrue(!called)
    }

    @Test fun anAnswerInsteadOfACorrectionIsRejected() {
        val raw = "what's the weather"
        val answer = "The weather today is sunny with a high of 24 degrees and a light breeze from the west."
        assertEquals(raw, LlmTranscriptCleaner.acceptCleaned(raw, answer))
        assertEquals(raw, LlmTranscriptCleaner.acceptCleaned(raw, "   "))
        assertEquals(raw, LlmTranscriptCleaner.acceptCleaned(raw, null))
    }

    @Test fun autoLanguageKeepsWhateverWasSpoken() = runBlocking {
        var sent: List<ChatMessage> = emptyList()
        LlmTranscriptCleaner({ sent = it; "Kya haal hai?" }, { null }, 1_000).clean("kya haal hai")
        val system = sent.first().textContent
        // No language is named, so a phone set to English can't turn Hindi into English.
        assertTrue(Language.entries.none { system.contains(it.label) })
        assertTrue(system.contains("same language as the text"))
    }

    @Test fun aShortAnswerInsteadOfACorrectionIsRejected() {
        val raw = "what time is it"
        assertEquals(raw, LlmTranscriptCleaner.acceptCleaned(raw, "I don't have access to the time."))
        // A real correction keeps the words, apostrophes and capitals aside.
        assertEquals("What's the time?", LlmTranscriptCleaner.acceptCleaned("whats the time", "What's the time?"))
    }

    @Test fun echoedTagsAndQuotesAreStripped() {
        assertEquals("What's the weather?", LlmTranscriptCleaner.acceptCleaned("whats the weather", "<dictation>\n\"What's the weather?\"\n</dictation>"))
    }
}
