package com.samosaai.masala.core

import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.ModePatch
import com.samosaai.masala.core.contract.SkillTier
import com.samosaai.masala.core.contract.sensitivity
import com.samosaai.masala.core.tool.ToolSensitivity
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContractsTest {

    @Test
    fun `every example round-trips`() {
        for (name in Fixtures.layoutExamples) {
            val doc = Fixtures.layout(name)
            assertEquals(doc, MasalaJson.strict.decodeFromString<LayoutDoc>(MasalaJson.strict.encodeToString(doc)), name)
        }
        for (name in Fixtures.modeExamples) {
            val mode = Fixtures.mode(name)
            assertEquals(mode, MasalaJson.strict.decodeFromString<ModePatch>(MasalaJson.strict.encodeToString(mode)), name)
        }
        for (name in Fixtures.skillExamples) {
            val skill = Fixtures.skill(name)
            assertEquals(skill, MasalaJson.lenient.decodeFromString<MasalaSkill>(MasalaJson.lenient.encodeToString(skill)), name)
        }
    }

    @Test
    fun `strict decoding rejects unknown fields`() {
        val json = """{"pages":[{"id":"p","sections":[{"type":"header","id":"h","colour":"red"}]}]}"""
        assertFailsWith<SerializationException> { MasalaJson.strict.decodeFromString<LayoutDoc>(json) }
    }

    @Test
    fun `strict decoding rejects unknown section types`() {
        val json = """{"pages":[{"id":"p","sections":[{"type":"carousel","id":"c"}]}]}"""
        assertFailsWith<SerializationException> { MasalaJson.strict.decodeFromString<LayoutDoc>(json) }
    }

    /** the private prototype's `Skill` fields only. Replace with the private prototype's own class once its code is in the repo. */
    @Serializable
    data class PrototypeSkill(
        val id: String,
        val title: String,
        val description: String = "",
        val instructions: String,
        val requiresTools: List<String> = emptyList(),
        val keywords: List<String> = emptyList(),
        val targetPackageNames: List<String> = emptyList(),
    )

    @Test
    fun `the private prototype can still read every Masala skill`() {
        for (name in Fixtures.skillExamples) {
            val prototype = MasalaJson.lenient.decodeFromString<PrototypeSkill>(Fixtures.text("skills/$name.json"))
            assertEquals(Fixtures.skill(name).instructions, prototype.instructions, name)
        }
    }

    @Test
    fun `Masala reads a plain the private prototype skill with defaults`() {
        val skill = MasalaJson.lenient.decodeFromString<MasalaSkill>(
            """{"id":"x","title":"X","instructions":"Do x","isVerified":true}""",
        )
        assertEquals(SkillTier.FREE, skill.tier)
        assertEquals(emptyList(), skill.platforms)
    }

    @Test
    fun `confirmation is decided per tool`() {
        val tools = mapOf("wiki_read" to ToolSensitivity.READ_ONLY, "delete_file" to ToolSensitivity.DESTRUCTIVE)
        assertEquals(ToolSensitivity.READ_ONLY, MasalaAction.RunTool("wiki_read").sensitivity(tools::get))
        assertEquals(ToolSensitivity.DESTRUCTIVE, MasalaAction.RunTool("delete_file").sensitivity(tools::get))
        assertEquals(ToolSensitivity.DESTRUCTIVE, MasalaAction.RunTool("unknown").sensitivity(tools::get))
        assertEquals(ToolSensitivity.CHANGES_SOMETHING, MasalaAction.SetAlarm(7, 0).sensitivity(tools::get))
        assertEquals(ToolSensitivity.READ_ONLY, MasalaAction.Dial("123").sensitivity(tools::get))
    }
}
