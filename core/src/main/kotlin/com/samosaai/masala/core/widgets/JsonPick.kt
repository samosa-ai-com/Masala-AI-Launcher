package com.samosaai.masala.core.widgets

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The small JSONPath subset an `http` binding may use: `$`, `.name`, `['name']`, `["name"]` and
 * `[n]` (a negative `n` counts from the end). No wildcards, filters or recursion, so a path always
 * names at most one value.
 */
object JsonPick {

    sealed interface Step {
        data class Key(val name: String) : Step
        data class Index(val index: Int) : Step
    }

    const val MAX_STEPS = 16

    /** The steps of [path]; throws [IllegalArgumentException] with the reason when it isn't valid. */
    fun compile(path: String): List<Step> {
        require(path.startsWith("$")) { "A path starts with \$" }
        val steps = mutableListOf<Step>()
        var i = 1
        while (i < path.length) {
            when (path[i]) {
                '.' -> {
                    val start = i + 1
                    var end = start
                    while (end < path.length && (path[end].isLetterOrDigit() || path[end] == '_' || path[end] == '-')) end++
                    require(end > start) { "Expected a name after '.' at ${i + 1}" }
                    steps += Step.Key(path.substring(start, end))
                    i = end
                }
                '[' -> {
                    val close = path.indexOf(']', i)
                    require(close > i) { "Unclosed '[' at ${i + 1}" }
                    val inner = path.substring(i + 1, close).trim()
                    steps += when {
                        inner.length >= 2 && (inner.first() == '\'' || inner.first() == '"') && inner.last() == inner.first() ->
                            Step.Key(inner.substring(1, inner.length - 1).also { require(it.isNotEmpty()) { "Empty name at ${i + 1}" } })
                        else -> Step.Index(requireNotNull(inner.toIntOrNull()) { "Expected a number or a quoted name at ${i + 1}" })
                    }
                    i = close + 1
                }
                else -> throw IllegalArgumentException("Unexpected '${path[i]}' at ${i + 1}")
            }
            require(steps.size <= MAX_STEPS) { "At most $MAX_STEPS steps" }
        }
        return steps
    }

    /** True when [path] compiles. */
    fun isValid(path: String): Boolean = runCatching { compile(path) }.isSuccess

    /** The value at [path] in [root], or null when it isn't there. */
    fun pick(root: JsonElement, path: String): JsonElement? = pick(root, compile(path))

    fun pick(root: JsonElement, steps: List<Step>): JsonElement? {
        var current: JsonElement = root
        for (step in steps) {
            current = when (step) {
                is Step.Key -> (current as? JsonObject)?.get(step.name) ?: return null
                is Step.Index -> {
                    val array = current as? JsonArray ?: return null
                    val index = if (step.index < 0) array.size + step.index else step.index
                    array.getOrNull(index) ?: return null
                }
            }
        }
        return current
    }

    /** A value as a widget shows it: strings unquoted, objects and arrays as compact JSON. */
    fun display(value: JsonElement?): String? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
}
