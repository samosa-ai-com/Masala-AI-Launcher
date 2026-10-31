package com.samosaai.masala.core

import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.FunctionTool
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolSensitivity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FunctionToolTest {
    private val definition = ToolDefinition("t", "test", JsonObject(emptyMap()))
    private val none = JsonObject(emptyMap())

    @Test
    fun `sensitivity decides by default`() {
        assertFalse(FunctionTool(definition, ToolSensitivity.READ_ONLY) { ActionResult.ok("") }.needsConfirmation(none))
        assertTrue(FunctionTool(definition, ToolSensitivity.CHANGES_SOMETHING) { ActionResult.ok("") }.needsConfirmation(none))
        assertTrue(FunctionTool(definition, ToolSensitivity.DESTRUCTIVE) { ActionResult.ok("") }.needsConfirmation(none))
    }

    @Test
    fun `a tool can ask only for some calls`() {
        val tool = FunctionTool(
            definition,
            ToolSensitivity.CHANGES_SOMETHING,
            confirmWhen = { (it["path"] as? JsonPrimitive)?.content?.startsWith("notes/2026") == true },
        ) { ActionResult.ok("") }
        assertFalse(tool.needsConfirmation(buildJsonObject { put("path", JsonPrimitive("people/a.md")) }))
        assertTrue(tool.needsConfirmation(buildJsonObject { put("path", JsonPrimitive("notes/2026-10-29.md")) }))
    }
}
