package com.samosaai.masala.skills

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillSearchToolTest {
    private val chef = MasalaSkill(id = "chef", title = "Chef", description = "Cooking help.", instructions = "Cook.")
    private val doctor = MasalaSkill(id = "doctor", title = "Doctor", description = "Health questions.", instructions = "Heal.")

    private fun call(tool: Tool, query: String): ToolResult = runBlocking {
        tool.execute(buildJsonObject { put("query", JsonPrimitive(query)) })
    }

    @Test
    fun `matching skills come back as one-liners without instructions`() {
        val tool = SkillSearchTool.create(search = { q ->
            listOf(chef, doctor).filter { it.id.contains(q) || it.description.contains(q) }
        })
        val result = call(tool, "chef")
        assertIs<ToolResult.Success>(result)
        assertTrue("• chef: Cooking help." in result.output)
        assertTrue("run_skill" in result.output)
        assertTrue("Cook." !in result.output)
    }

    @Test
    fun `no match says so plainly`() {
        val tool = SkillSearchTool.create(search = { emptyList() })
        val result = call(tool, "spaceship")
        assertIs<ToolResult.Success>(result)
        assertTrue("No installed skill matches" in result.output)
    }

    @Test
    fun `missing query fails`() {
        val tool = SkillSearchTool.create(search = { emptyList() })
        assertIs<ToolResult.Failure>(runBlocking { tool.execute(JsonObject(emptyMap())) })
    }

    @Test
    fun `results are capped at thirty`() {
        val many = (1..40).map { MasalaSkill(id = "s$it", title = "S$it", description = "D$it", instructions = "I") }
        val tool = SkillSearchTool.create(search = { many })
        val result = call(tool, "s")
        assertIs<ToolResult.Success>(result)
        assertTrue("• s30:" in result.output)
        assertTrue("• s31:" !in result.output)
    }

    @Test
    fun `experts the user hid stay hidden, jobs do not`() {
        val hidden = MasalaSkill(
            id = "doctor", title = "Doctor", description = "Health.", instructions = "Heal.",
            persona = com.samosaai.masala.core.contract.Persona("You are a GP."),
        )
        val tool = SkillSearchTool.create({ listOf(chef, hidden) }, hiddenIds = { setOf("doctor", "chef") })
        val result = call(tool, "e")
        assertIs<ToolResult.Success>(result)
        assertTrue("• chef:" in result.output, result.output)
        assertTrue("doctor" !in result.output, result.output)
    }
}
