package com.samosaai.masala.agent

import com.samosaai.masala.core.tool.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the model API receives as a tool's parameters. */
class ToolSchemaTest {
    @Test
    fun anArgumentFreeToolStillSendsProperties() {
        // LM Studio answers HTTP 400 for {"type":"object"} without "properties".
        val sent = ToolDefinition("status", "d", buildJsonObject { put("type", "object") }).toLlm()
        assertEquals(JsonObject(emptyMap()), sent.function.parameters["properties"])
        assertEquals(JsonPrimitive("object"), sent.function.parameters["type"])
    }

    @Test
    fun existingPropertiesAreLeftAlone() {
        val params = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("id") { put("type", "string") } }
        }
        assertEquals(params, ToolDefinition("open", "d", params).toLlm().function.parameters)
    }
}
