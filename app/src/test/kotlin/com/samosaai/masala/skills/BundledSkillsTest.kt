package com.samosaai.masala.skills

import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.agent.AgentTools
import com.samosaai.masala.agent.MemoryTools
import com.samosaai.masala.core.tool.SkillImportValidator
import com.samosaai.masala.tools.MasalaToolDefinitions
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
        // The game maker and the daily brief make something rather than hold a conversation.
        assertEquals(setOf("daily_brief", "game_maker"), skills.filter { it.persona == null }.map { it.id }.toSet())
    }

    @Test
    fun `every tool a bundled skill asks for is one Masala has`() {
        val memory = listOf(MemoryTools.read, MemoryTools.search, MemoryTools.write, MemoryTools.edit, MemoryTools.delete, MemoryTools.link)
        val agent = listOf(AgentTools.editLayout, AgentTools.setMode, AgentTools.runSkill, AgentTools.composeMessage)
        val known = (MasalaToolDefinitions.all + memory + agent).map { it.name }.toSet()
        skills.forEach { skill ->
            assertEquals(emptyList(), skill.requiresTools.filterNot { it in known }, "${skill.id} names tools Masala doesn't have")
        }
    }

    @Test
    fun `the daily brief's contract example in core matches the shipped skill`() {
        // :core keeps a copy for its contract tests; the two must not drift.
        assertEquals(File("src/main/assets/skills/daily_brief.json").readText(), File("../core/src/main/resources/masala/skills/daily_brief.json").readText())
    }

    @Test
    fun `the daily brief runs on a schedule and shows Markdown`() {
        val brief = skills.single { it.id == "daily_brief" }
        assertEquals("daily 07:30", brief.schedule?.default)
        assertEquals(com.samosaai.masala.core.contract.SkillOutput.MARKDOWN, brief.widget?.output)
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
