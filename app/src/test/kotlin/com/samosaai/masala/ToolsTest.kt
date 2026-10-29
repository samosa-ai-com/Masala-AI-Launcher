package com.samosaai.masala

import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.FunctionTool
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.core.tool.FileAccess
import com.samosaai.masala.core.tool.FileAccessDeniedException
import com.samosaai.masala.core.tool.FileScope
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.ChatRequest
import com.samosaai.masala.llm.ResponseFormat
import com.samosaai.masala.tools.Capability
import com.samosaai.masala.tools.MasalaToolDefinitions
import com.samosaai.masala.tools.MasalaToolRegistry
import com.samosaai.masala.tools.boolean
import com.samosaai.masala.tools.int
import com.samosaai.masala.tools.stringList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolsTest {

    private object NoFiles : FileAccess {
        override val scope = FileScope.APP_STORAGE
        override fun resolve(path: String) = File(path)
        override fun outputDir() = File(".")
    }

    private fun tool(name: String) =
        FunctionTool(ToolDefinition(name, "", JsonObject(emptyMap())), ToolSensitivity.READ_ONLY) { ActionResult.ok(name) }

    @Test
    fun `the private prototype results map onto core results`() {
        assertEquals(ToolResult.Success("done"), ActionResult.ok("done").toToolResult())
        assertEquals(ToolResult.Failure("no"), ActionResult.error("no").toToolResult())
        assertEquals(
            ToolResult.NeedsCapability(ActionResult.USAGE_ACCESS, "grant it"),
            ActionResult.permissionNeeded(ActionResult.USAGE_ACCESS, "grant it").toToolResult(),
        )
    }

    @Test
    fun `arguments tolerate what models send`() {
        val args = buildJsonObject {
            put("hour", "7")
            put("vibrate", "false")
            put("days", Json.parseToJsonElement("""["mon","fri"]"""))
            put("bad_list", "mon")
        }
        assertEquals(7, args.int("hour"))
        assertEquals(false, args.boolean("vibrate"))
        assertEquals(listOf("mon", "fri"), args.stringList("days"))
        assertNull(args.stringList("bad_list"))
        assertNull(args.int("missing"))
    }

    @Test
    fun `the dynamic slot adds and removes tools by owner, and can't shadow fixed ones`() {
        val registry = MasalaToolRegistry(NoFiles)
        registry.register(tool("set_alarm"))
        registry.setDynamic("mcp:notes", listOf(tool("notes_search")))
        assertEquals(listOf("set_alarm", "notes_search"), registry.tools.map(Tool::definition).map { it.name })
        assertEquals("notes_search", registry["notes_search"]?.definition?.name)

        registry.setDynamic("mcp:notes", emptyList())
        assertNull(registry["notes_search"])
        assertFailsWith<IllegalStateException> { registry.setDynamic("mcp:x", listOf(tool("set_alarm"))) }
        assertFailsWith<IllegalArgumentException> { registry.register(tool("set_alarm")) }
    }

    @Test
    fun `every definition is a well-formed object schema with a unique name`() {
        val defs = MasalaToolDefinitions.all
        assertEquals(defs.size, defs.map { it.name }.toSet().size, "duplicate tool names")
        for (def in defs) {
            assertEquals("object", def.parameters["type"]?.jsonPrimitive?.content, def.name)
            assertTrue(def.description.isNotBlank(), def.name)
            val properties = def.parameters["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val required = def.parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            assertTrue(properties.keys.containsAll(required), "${def.name} requires an undeclared parameter")
        }
    }

    @Test
    fun `definitions don't point the model at the private prototype tools Masala left out`() {
        val leftOut = listOf(
            "call_number", "find_contact", "read_call_log", "list_alarms", "list_timers", "edit_alarm",
            "delete_alarm", "delete_timer", "set_brightness",
        )
        for (def in MasalaToolDefinitions.all) {
            assertEquals(emptyList(), leftOut.filter { it in def.description }, def.name)
        }
    }

    @Test
    fun `the Lane D follow-up tools are registered`() {
        val names = MasalaToolDefinitions.all.map { it.name }.toSet()
        for (expected in listOf(
            "dismiss_alarm", "open_url", "create_calendar_event", "search_contacts",
            "list_mini_apps", "open_mini_app", "pin_mini_app", "delete_mini_app",
        )) {
            assertTrue(expected in names, "missing tool $expected")
        }
    }

    @Test
    fun `alarm tools say plainly that a scheduled alarm cannot be deleted`() {
        val set = MasalaToolDefinitions.all.single { it.name == "set_alarm" }
        assertTrue("no API" in set.description, set.description)
        val dismiss = MasalaToolDefinitions.all.single { it.name == "dismiss_alarm" }
        assertTrue("no API" in dismiss.description, dismiss.description)
        assertTrue("show_alarms" in dismiss.description, dismiss.description)
    }

    @Test
    fun `response_format is sent only when asked for`() {
        val json = Json { explicitNulls = false; encodeDefaults = false }
        val plain = json.encodeToString(ChatRequest("m", listOf(ChatMessage("user", JsonPrimitive("hi")))))
        assertTrue("response_format" !in plain)

        val schema = buildJsonObject { put("type", "object") }
        val structured = json.parseToJsonElement(
            json.encodeToString(ChatRequest("m", emptyList(), responseFormat = ResponseFormat.jsonSchema("layout", schema))),
        ).jsonObject["response_format"]!!.jsonObject
        assertEquals("json_schema", structured["type"]?.jsonPrimitive?.content)
        assertEquals("layout", structured["json_schema"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `files outside app storage ask for all-files access, unless pro already has it`() {
        fun files(scope: FileScope) = ToolFiles {
            object : FileAccess {
                override val scope = scope
                override fun resolve(path: String) = if (path.startsWith("/app")) File(path) else throw FileAccessDeniedException(path, "outside")
                override fun outputDir() = File("/app/output")
            }
        }
        assertEquals(ToolFiles.ResolveResult.Ok(File("/app/a.pdf")), files(FileScope.APP_STORAGE).resolveForRead("/app/a.pdf"))
        val denied = files(FileScope.APP_STORAGE).resolveForRead("/sdcard/a.pdf") as ToolFiles.ResolveResult.PermissionNeeded
        assertEquals(ToolFiles.ALL_FILES_ACCESS, denied.result.needsPermission)
        assertTrue(files(FileScope.SHARED_STORAGE).resolveForRead("/sdcard/a.pdf") is ToolFiles.ResolveResult.Error)
        assertEquals(ToolFiles.ALL_FILES_ACCESS, Capability.ALL_FILES.marker)
    }

    @Test
    fun `chat request carries max tokens for layout calls`() {
        val json = Json { explicitNulls = false; encodeDefaults = false }
        val encoded = json.encodeToString(
            ChatRequest("m", emptyList(), responseFormat = ResponseFormat.JSON_OBJECT, maxTokens = 3000)
        )
        assertTrue(encoded.contains("\"max_tokens\":3000"), encoded)
        assertTrue(encoded.contains("\"response_format\""), encoded)
    }
}
