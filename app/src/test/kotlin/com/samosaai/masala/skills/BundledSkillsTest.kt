package com.samosaai.masala.skills

import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.SkillImportValidator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan Phase 1 tests: every bundled skill decodes with the lenient reader and passes the validator. */
class BundledSkillsTest {
    private val files = File("src/main/assets/skills").walkTopDown().filter { it.isFile && it.extension == "json" }.toList()

    private val skills = files.map { MasalaJson.lenient.decodeFromString<MasalaSkill>(it.readText()) }

    @Test
    fun `the six personas and the game maker are bundled`() {
        assertEquals(
            setOf("chef", "doctor", "fitness_coach", "handyman", "travel_planner", "tutor"),
            skills.filter { it.persona != null }.map { it.id }.toSet(),
        )
        // The game maker builds an app rather than holding a conversation: the one skill without a persona.
        assertEquals(listOf("game_maker"), skills.filter { it.persona == null }.map { it.id })
    }

    @Test
    fun `every bundled skill passes the validator`() {
        assertTrue(skills.isNotEmpty())
        skills.forEach { SkillImportValidator.validate(it) }
    }

    @Test
    fun `file names match skill ids and ids are unique`() {
        files.zip(skills).forEach { (file, skill) -> assertEquals(file.nameWithoutExtension, skill.id) }
        assertEquals(skills.size, skills.map { it.id }.toSet().size)
    }

    @Test
    fun `every bundled skill is made for Masala`() {
        skills.forEach { assertTrue("masala" in it.platforms, "${it.id} lacks the masala platform") }
    }
}
