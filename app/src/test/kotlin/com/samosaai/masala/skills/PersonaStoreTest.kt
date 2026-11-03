package com.samosaai.masala.skills

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonaStoreTest {
    private val root: File = createTempDirectory("personas").toFile()

    @Test
    fun nothingSavedMeansAsShipped() {
        val store = PersonaStore(root)
        assertNull(store.promptFor("doctor"))
        assertEquals(emptySet(), store.toolsFor("doctor"))
    }

    @Test
    fun aPromptAToolListAndAWhenLineSurviveAReload() {
        PersonaStore(root).save(
            "doctor",
            PersonaOverride(
                systemPrompt = "Answer in one line.",
                allowTools = listOf("memory_read"),
                whenToUse = "Anything about the body.",
            ),
        )
        val reopened = PersonaStore(root)
        assertEquals("Answer in one line.", reopened.promptFor("doctor"))
        assertEquals(setOf("memory_read"), reopened.toolsFor("doctor"))
        assertEquals("Anything about the body.", reopened.whenToUseFor("doctor"))
    }

    @Test
    fun whenToUseIsIndependentOfTheOtherEdits() {
        val store = PersonaStore(root)
        store.save("chef", PersonaOverride(whenToUse = "Dinner ideas."))
        assertEquals("Dinner ideas.", store.whenToUseFor("chef"))
        assertNull(store.promptFor("chef"))
        assertEquals(emptySet(), store.toolsFor("chef"))
        store.reset("chef")
        assertNull(store.whenToUseFor("chef"))
    }

    @Test
    fun anOverrideWithEverythingNullIsRemoved() {
        val store = PersonaStore(root)
        store.save("doctor", PersonaOverride(systemPrompt = "one line"))
        store.save("doctor", PersonaOverride())
        assertNull(store.promptFor("doctor"))
        assertEquals(emptyMap(), store.read())
    }

    @Test
    fun resetRestoresTheShippedPrompt() {
        val store = PersonaStore(root)
        store.save("doctor", PersonaOverride(systemPrompt = "one line"))
        store.reset("doctor")
        assertNull(store.promptFor("doctor"))
        assertTrue(PersonaStore(root).read().isEmpty())
    }

    @Test
    fun hidingAnExpertKeepsItsEditsAndCanBeRestored() {
        val store = PersonaStore(root)
        store.save("doctor", PersonaOverride(systemPrompt = "short answers", whenToUse = "anything medical"))
        store.setHidden("doctor", true)
        assertTrue(store.isHidden("doctor"))
        assertEquals(setOf("doctor"), store.hiddenIds())
        // Hiding is its own flag: the edits are still there behind it.
        assertEquals("short answers", store.promptFor("doctor"))
        assertEquals("anything medical", store.whenToUseFor("doctor"))
        store.setHidden("doctor", false)
        // Showing it again keeps what the user wrote; only the flag goes.
        assertEquals("short answers", store.promptFor("doctor"))
        assertEquals(emptySet(), store.hiddenIds())

        // With nothing else changed, hide then show leaves no override at all.
        val plain = PersonaStore(root)
        plain.setHidden("chef", true)
        plain.setHidden("chef", false)
        assertNull(plain.override("chef"))
    }

    @Test
    fun aCorruptFileReadsAsNothingChanged() {
        root.mkdirs()
        File(root, "overrides.json").writeText("{ not json")
        assertNull(PersonaStore(root).promptFor("doctor"))
    }

    @Test
    fun oneExpertDoesNotAffectAnother() {
        val store = PersonaStore(root)
        store.save("doctor", PersonaOverride(systemPrompt = "doctor voice"))
        store.save("chef", PersonaOverride(systemPrompt = "chef voice"))
        assertEquals("doctor voice", store.promptFor("doctor"))
        assertEquals("chef voice", store.promptFor("chef"))
        store.reset("doctor")
        assertEquals("chef voice", store.promptFor("chef"))
    }
}
