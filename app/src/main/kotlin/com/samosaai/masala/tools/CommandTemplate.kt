package com.samosaai.masala.tools

import com.samosaai.masala.core.tool.ToolDefinition
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A ready-to-edit argument object for the `/tool` prompt: one entry per required parameter, with a
 * placeholder of the right JSON type, so tapping "Use" always produces a call that parses. Lives in
 * main (not the debug screen) so every build variant — and the unit tests — can use it.
 */
internal fun commandTemplate(definition: ToolDefinition): String {
    val properties = definition.parameters["properties"]?.jsonObject.orEmpty()
    val required = definition.parameters["required"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    val args = required.joinToString(",") { key ->
        val type = properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull ?: "string"
        val value = when (type) {
            "integer", "number" -> "0"
            "boolean" -> "false"
            "array" -> "[]"
            "object" -> "{}"
            else -> "\"\""
        }
        "\"$key\":$value"
    }
    return "{$args}"
}
