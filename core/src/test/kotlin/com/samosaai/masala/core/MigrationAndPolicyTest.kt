package com.samosaai.masala.core

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.migration.LayoutMigrations
import com.samosaai.masala.core.tool.DefaultToolRegistry
import com.samosaai.masala.core.tool.FileAccess
import com.samosaai.masala.core.tool.FileScope
import com.samosaai.masala.core.tool.HubSkillPolicy
import com.samosaai.masala.core.tool.HubSkillPolicy.Decision
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolOrigin
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MigrationAndPolicyTest {

    @Test
    fun `version 1 needs no migration, and a missing version means 1`() {
        val classic = Json.parseToJsonElement(Fixtures.text("examples/classic.json"))
        assertEquals(Fixtures.layout("classic"), LayoutMigrations.decode(classic))

        val unversioned = JsonObject((classic as JsonObject) - "schemaVersion")
        assertEquals(Fixtures.layout("classic"), LayoutMigrations.decode(unversioned))
    }

    @Test
    fun `a layout from a newer app is refused`() {
        val future = Json.parseToJsonElement("""{"schemaVersion":99,"pages":[]}""")
        assertFailsWith<IllegalArgumentException> { LayoutMigrations.decode(future) }
    }

    private class FakeTool(
        name: String,
        override val origin: ToolOrigin = ToolOrigin.CORE,
        override val readsPrivateData: Boolean = false,
        override val usesNetwork: Boolean = false,
    ) : Tool {
        override val definition = ToolDefinition(name, name, JsonObject(emptyMap()))
        override val sensitivity = ToolSensitivity.READ_ONLY
        override suspend fun execute(args: JsonObject) = ToolResult.Success("")
    }

    private val registry = DefaultToolRegistry(
        object : FileAccess {
            override val scope = FileScope.APP_STORAGE
            override fun resolve(path: String) = File(path)
            override fun outputDir() = File(".")
        },
    ).apply {
        register(FakeTool("memory_read", readsPrivateData = true))
        register(FakeTool("calendar_read", readsPrivateData = true))
        register(FakeTool("weather", usesNetwork = true))
        register(FakeTool("websearch", usesNetwork = true))
        register(FakeTool("webfetch", usesNetwork = true))
        register(FakeTool("file_search", origin = ToolOrigin.PRO, readsPrivateData = true))
    }

    private fun skill(tools: List<String>, platforms: List<String> = listOf("masala"), instructions: String = "Help.") =
        MasalaSkill(id = "s", title = "S", instructions = instructions, requiresTools = tools, platforms = platforms)

    @Test
    fun `hub skill decisions`() {
        assertEquals(Decision.Install, HubSkillPolicy.decide(skill(listOf("websearch")), registry))
        assertIs<Decision.Reject>(HubSkillPolicy.decide(skill(listOf("file_search")), registry))
        assertIs<Decision.Reject>(HubSkillPolicy.decide(skill(listOf("send_sms")), registry))
        assertIs<Decision.Reject>(HubSkillPolicy.decide(skill(emptyList(), platforms = listOf("prototype")), registry))
        assertIs<Decision.UserOnly>(HubSkillPolicy.decide(skill(listOf("memory_read", "webfetch")), registry))
    }

    @Test
    fun `old hub skills that teach UI steps are rejected`() {
        val ui = skill(emptyList(), platforms = emptyList(), instructions = "Open WhatsApp, then tap the search icon.")
        assertIs<Decision.Reject>(HubSkillPolicy.decide(ui, registry))
        val plain = skill(emptyList(), platforms = emptyList(), instructions = "Summarise the text the user gives you.")
        assertEquals(Decision.Install, HubSkillPolicy.decide(plain, registry))
    }

    @Test
    fun `the built-in daily brief would need the user's approval as a hub skill`() {
        assertIs<Decision.UserOnly>(HubSkillPolicy.decide(Fixtures.skill("daily_brief"), registry))
    }

    @Test
    fun `registry refuses duplicate names`() {
        assertFailsWith<IllegalArgumentException> { registry.register(FakeTool("websearch")) }
    }
}
