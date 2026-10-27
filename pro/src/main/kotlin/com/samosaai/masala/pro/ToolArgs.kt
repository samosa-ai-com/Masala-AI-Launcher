package com.samosaai.masala.pro

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

// Argument readers from the private prototype's ToolExecutor, the same as `:app`'s Args.kt (which `:pro` can't see).
// Models send numbers as strings and leave out optional arguments, so each returns null rather than throwing.

internal fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.takeIf { it.isString || it.content.isNotEmpty() }?.content

/** Tolerates numbers sent as JSON strings. */
internal fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.let { it.intOrNull ?: it.content.trim().toIntOrNull() }

internal fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.jsonPrimitive?.let { it.booleanOrNull ?: it.content.trim().lowercase().toBooleanStrictOrNull() }
