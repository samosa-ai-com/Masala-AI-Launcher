package com.samosaai.masala.core

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.Persona
import com.samosaai.masala.core.contract.SkillSchedule
import com.samosaai.masala.core.contract.SkillWidget
import com.samosaai.masala.core.tool.SkillImportValidator
import com.samosaai.masala.core.tool.SkillValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** From the private prototype's `SkillImportValidatorTest`, on [MasalaSkill], plus Masala's fields. */
class SkillImportValidatorTest {

    private val base = MasalaSkill(
        id = "good_id",
        instructions = "Do X then Y.",
        description = "A useful skill",
        title = "Good Skill"
    )

    @Test
    fun `accepts a well-formed skill`() {
        SkillImportValidator.validate(base)
    }

    @Test
    fun `rejects empty id`() {
        assertFailsWithMessage("id is empty") {
            SkillImportValidator.validate(base.copy(id = ""))
        }
    }

    @Test
    fun `rejects id with uppercase`() {
        assertFailsWithMessage("id must match") {
            SkillImportValidator.validate(base.copy(id = "BadId"))
        }
    }

    @Test
    fun `rejects id starting with digit`() {
        assertFailsWithMessage("id must match") {
            SkillImportValidator.validate(base.copy(id = "1bad"))
        }
    }

    @Test
    fun `rejects id that is too short`() {
        assertFailsWithMessage("id must match") {
            SkillImportValidator.validate(base.copy(id = "ab"))
        }
    }

    @Test
    fun `rejects blank instructions`() {
        assertFailsWithMessage("instructions is empty") {
            SkillImportValidator.validate(base.copy(instructions = "   "))
        }
    }

    @Test
    fun `rejects oversized instructions`() {
        val giant = "x".repeat(SkillImportValidator.MAX_INSTRUCTIONS_LENGTH + 1)
        assertFailsWithMessage("exceed") {
            SkillImportValidator.validate(base.copy(instructions = giant))
        }
    }

    @Test
    fun `rejects malformed target package name`() {
        assertFailsWithMessage("invalid target package") {
            SkillImportValidator.validate(base.copy(targetPackageNames = listOf("not.a.valid..pkg")))
        }
    }

    @Test
    fun `accepts wildcard target package`() {
        SkillImportValidator.validate(base.copy(targetPackageNames = listOf("*")))
    }

    @Test
    fun `rejects ESCOBEL control chars in instructions`() {
        // Vertical tab is a control char.
        val bad = "foo\u000Bbar"
        assertFailsWithMessage("control characters") {
            SkillImportValidator.validate(base.copy(instructions = bad))
        }
    }

    @Test
    fun `sanitize preserves conventional whitespace`() {
        val ok = SkillImportValidator.sanitize("line1\nline2\tcolumn")
        assertEquals("line1\nline2\tcolumn", ok)
    }

    @Test
    fun `sanitize strips zero-width chars`() {
        val cleaned = SkillImportValidator.sanitize("a​b‌c")
        assertEquals("abc", cleaned)
    }

    // ---- Masala's fields ----

    private val persona = Persona(systemPrompt = "You are a chef.", starters = listOf("What can I make?"))

    @Test
    fun `accepts a persona, widget and schedule`() {
        SkillImportValidator.validate(
            base.copy(
                platforms = listOf("masala"),
                persona = persona.copy(disclaimer = "Not medical advice."),
                widget = SkillWidget(icon = "restaurant", tint = "orange"),
                schedule = SkillSchedule("daily 07:30")
            )
        )
    }

    @Test
    fun `rejects a blank persona prompt`() {
        assertFailsWithMessage("persona.systemPrompt is empty") {
            SkillImportValidator.validate(base.copy(persona = persona.copy(systemPrompt = " ")))
        }
    }

    @Test
    fun `rejects control characters in the persona prompt`() {
        assertFailsWithMessage("persona.systemPrompt contains control") {
            SkillImportValidator.validate(base.copy(persona = persona.copy(systemPrompt = "a​b")))
        }
    }

    @Test
    fun `rejects an oversized persona prompt`() {
        val giant = "x".repeat(SkillImportValidator.MAX_PERSONA_PROMPT_LENGTH + 1)
        assertFailsWithMessage("persona.systemPrompt exceeds") {
            SkillImportValidator.validate(base.copy(persona = persona.copy(systemPrompt = giant)))
        }
    }

    @Test
    fun `rejects too many, empty, long or control-character starters`() {
        val many = List(SkillImportValidator.MAX_STARTERS + 1) { "starter $it" }
        assertFailsWithMessage("persona.starters exceeds") {
            SkillImportValidator.validate(base.copy(persona = persona.copy(starters = many)))
        }
        assertFailsWithMessage("empty entry") {
            SkillImportValidator.validate(base.copy(persona = persona.copy(starters = listOf(" "))))
        }
        assertFailsWithMessage("starter exceeds") {
            SkillImportValidator.validate(
                base.copy(persona = persona.copy(starters = listOf("x".repeat(SkillImportValidator.MAX_STARTER_LENGTH + 1))))
            )
        }
        assertFailsWithMessage("persona.starters contains control") {
            SkillImportValidator.validate(base.copy(persona = persona.copy(starters = listOf("a\u0007b"))))
        }
    }

    @Test
    fun `rejects an oversized disclaimer`() {
        val giant = "x".repeat(SkillImportValidator.MAX_DISCLAIMER_LENGTH + 1)
        assertFailsWithMessage("persona.disclaimer exceeds") {
            SkillImportValidator.validate(base.copy(persona = persona.copy(disclaimer = giant)))
        }
    }

    @Test
    fun `rejects widget icon or tint that is not a plain name`() {
        assertFailsWithMessage("widget.icon") {
            SkillImportValidator.validate(base.copy(widget = SkillWidget(icon = "https://evil.example/x.png")))
        }
        assertFailsWithMessage("widget.tint") {
            SkillImportValidator.validate(base.copy(widget = SkillWidget(icon = "mic", tint = "#ff0000")))
        }
    }

    @Test
    fun `rejects malformed platforms`() {
        assertFailsWithMessage("invalid platform") {
            SkillImportValidator.validate(base.copy(platforms = listOf("Masala!")))
        }
        assertFailsWithMessage("platforms exceeds") {
            SkillImportValidator.validate(base.copy(platforms = List(SkillImportValidator.MAX_PLATFORMS + 1) { "p$it" }))
        }
    }

    @Test
    fun `accepts the three schedule forms and rejects the rest`() {
        listOf("daily 07:30", "weekly mon 18:00", "monthly 15 09:05", "monthly 1 00:00").forEach {
            SkillImportValidator.validate(base.copy(schedule = SkillSchedule(it)))
        }
        listOf("", "daily", "daily 7:30", "daily 24:00", "weekly funday 07:30", "monthly 32 07:30", "every day 07:30").forEach {
            assertFailsWithMessage("schedule.default") {
                SkillImportValidator.validate(base.copy(schedule = SkillSchedule(it)))
            }
        }
    }

    private fun assertFailsWithMessage(needle: String, block: () -> Unit) {
        try {
            block()
            fail("Expected SkillValidationException containing '$needle'")
        } catch (e: SkillValidationException) {
            assertTrue(
                e.message?.contains(needle, ignoreCase = true) == true,
                "Expected message to contain '$needle' but was '${e.message}'"
            )
        }
    }
}
