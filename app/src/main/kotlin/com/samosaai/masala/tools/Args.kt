package com.samosaai.masala.tools

import com.samosaai.masala.core.tool.ActionResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// Argument readers from the private prototype's ToolExecutor. Models send numbers as strings and leave out optional
// arguments, so each returns null rather than throwing.

internal fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.takeIf { it.isString || it.content.isNotEmpty() }?.content

/** Tolerates numbers sent as JSON strings. */
internal fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.let { it.intOrNull ?: it.content.trim().toIntOrNull() }

internal fun JsonObject.double(key: String): Double? =
    this[key]?.jsonPrimitive?.let { it.doubleOrNull ?: it.content.trim().toDoubleOrNull() }

internal fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.jsonPrimitive?.let { it.booleanOrNull ?: it.content.trim().lowercase().toBooleanStrictOrNull() }

internal fun JsonObject.stringList(key: String): List<String>? =
    runCatching { this[key]?.jsonArray?.map { it.jsonPrimitive.content } }.getOrNull()

internal fun missing(param: String) = ActionResult.error("Missing or invalid required parameter '$param'.")
