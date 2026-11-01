package com.samosaai.masala.widgets

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject


/**
 * The `json_schema` the widget generator asks for (plan `docs/plans/widget-maker.md` §7.3). The
 * shape mirrors what `WidgetChecker.parse` decodes (same `"type"` discriminators, same field
 * names), so a valid reply parses with no translation step.
 *
 * Only what the home renderer draws in W1 is included: the native components except `button`
 * actions beyond three safe ones, and no `video`/`snapshot`/`web_value` (W4/W5), no rules (the
 * manager's JSON editor adds those). Strict-mode: every object lists its properties in `required`
 * and sets `additionalProperties: false`.
 */
object WidgetSchema {

    fun schema(skillIds: Set<String> = emptySet()): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("id", string("A short slug id, e.g. water"))
            put("title", string("Short title shown on home"))
            putJsonObject("components") {
                put("type", "array")
                putJsonObject("items") {
                    putJsonArray("anyOf") {
                        componentTypes().forEach { addJsonObject { merge(it) } }
                    }
                }
            }
            putJsonObject("size") { merge(size()) }
        }
        putJsonArray("required") { add("id"); add("title"); add("components"); add("size") }
        put("additionalProperties", false)
    }

    private fun componentTypes(): List<JsonObject> = listOf(
        text(), counter(), checklist(), timer(), progress(), chart(), button(), image(),
        listRows(), gauge(), row(), column(),
    )

    private fun text(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("text"))
            put("text", string("Fixed text, or missing when binding is set"))
            putJsonObject("binding") { merge(binding()) }
            putJsonObject("style") { merge(style()) }
        }
        putJsonArray("required") { add("type") }
        put("additionalProperties", false)
    }

    private fun counter(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("counter"))
            put("key", string("Storage key, owned by this counter"))
            put("label", string())
            put("step", int("Added per tap"))
            put("goal", int("Optional target shown as n / goal"))
            putJsonObject("style") { merge(style()) }
        }
        putJsonArray("required") { add("type"); add("key"); add("label") }
        put("additionalProperties", false)
    }

    private fun checklist(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("checklist"))
            put("key", string("Storage key, owned by this list"))
            putJsonObject("items") {
                put("type", "array")
                putJsonObject("items") { merge(string()) }
            }
        }
        putJsonArray("required") { add("type"); add("key"); add("items") }
        put("additionalProperties", false)
    }

    private fun timer(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("timer"))
            put("key", string("Storage key holding the running deadline"))
            put("label", string())
            put("seconds", int("Countdown length"))
        }
        putJsonArray("required") { add("type"); add("key"); add("label"); add("seconds") }
        put("additionalProperties", false)
    }

    private fun progress(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("progress"))
            put("label", string())
            putJsonObject("binding") { merge(binding()) }
            put("max", number("Full bar value"))
            putJsonObject("style") { merge(style()) }
        }
        putJsonArray("required") { add("type"); add("label"); add("binding"); add("max") }
        put("additionalProperties", false)
    }

    private fun chart(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("chart"))
            putJsonObject("binding") { merge(binding()) }
            put("kind", enumOf("bar", "line"))
            put("days", int("How many values, oldest first, comma-separated in storage"))
        }
        putJsonArray("required") { add("type"); add("binding") }
        put("additionalProperties", false)
    }

    private fun button(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("button"))
            put("label", string())
            putJsonObject("action") {
                putJsonArray("anyOf") {
                    addJsonObject { merge(askMasala()) }
                    addJsonObject { merge(runSkill()) }
                    addJsonObject { merge(startTimer()) }
                }
            }
        }
        putJsonArray("required") { add("type"); add("label"); add("action") }
        put("additionalProperties", false)
    }

    private fun image(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("image"))
            putJsonObject("icon") {
                put("type", "object")
                putJsonObject("properties") {
                    put("type", const("symbol"))
                    put("name", string("A built-in symbol name, e.g. star, timer, checklist"))
                }
                putJsonArray("required") { add("type"); add("name") }
                put("additionalProperties", false)
            }
        }
        putJsonArray("required") { add("type"); add("icon") }
        put("additionalProperties", false)
    }

    private fun listRows(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("list"))
            putJsonObject("binding") { merge(binding()) }
            put("maxRows", int("At most 8"))
        }
        putJsonArray("required") { add("type"); add("binding") }
        put("additionalProperties", false)
    }

    private fun gauge(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("gauge"))
            put("label", string())
            putJsonObject("binding") { merge(binding()) }
            put("max", number("Full ring value"))
            putJsonObject("style") { merge(style()) }
        }
        putJsonArray("required") { add("type"); add("label"); add("binding"); add("max") }
        put("additionalProperties", false)
    }

    private fun row(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("row"))
            putJsonObject("children") {
                put("type", "array")
                putJsonObject("items") { merge(leaf()) }
            }
        }
        putJsonArray("required") { add("type"); add("children") }
        put("additionalProperties", false)
    }

    private fun column(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("column"))
            putJsonObject("children") {
                put("type", "array")
                putJsonObject("items") { merge(leaf()) }
            }
        }
        putJsonArray("required") { add("type"); add("children") }
        put("additionalProperties", false)
    }

    /** Row/column children: leaves only, so nesting stays within the checker's depth limit. */
    private fun leaf(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", string())
        }
        putJsonArray("required") { add("type") }
        put("additionalProperties", true)
    }

    private fun binding(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("source", enumOf("storage", "next_event", "battery", "steps", "sleep", "weather", "urgent_count"))
            put("key", string("Storage key (source storage), else missing"))
        }
        putJsonArray("required") { add("source") }
        put("additionalProperties", false)
    }

    private fun style(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("tint", enumOf("accent", "blue", "green", "amber", "red"))
            put("emphasis", enumOf("normal", "large"))
            put("align", enumOf("start", "center", "end"))
        }
        putJsonArray("required") { }
        put("additionalProperties", false)
    }

    private fun size(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("min", int())
            put("default", int())
            put("max", int())
        }
        putJsonArray("required") { add("min"); add("default"); add("max") }
        put("additionalProperties", false)
    }

    private fun askMasala(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("ask_masala"))
            put("prompt", string("What to ask Masala when tapped"))
        }
        putJsonArray("required") { add("type"); add("prompt") }
        put("additionalProperties", false)
    }

    private fun runSkill(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("run_skill"))
            put("skillId", string("An installed skill id, exactly as listed"))
        }
        putJsonArray("required") { add("type"); add("skillId") }
        put("additionalProperties", false)
    }

    private fun startTimer(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("start_timer"))
            put("seconds", int())
        }
        putJsonArray("required") { add("type"); add("seconds") }
        put("additionalProperties", false)
    }

    private fun string(description: String = "") = buildJsonObject {
        put("type", "string")
        if (description.isNotEmpty()) put("description", description)
    }

    private fun int(description: String = "") = buildJsonObject {
        put("type", "integer")
        if (description.isNotEmpty()) put("description", description)
    }

    private fun number(description: String = "") = buildJsonObject {
        put("type", "number")
        if (description.isNotEmpty()) put("description", description)
    }

    private fun const(value: String) = buildJsonObject {
        put("const", value)
    }

    private fun enumOf(vararg values: String) = buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(it) } }
    }

    private fun JsonObjectBuilder.merge(other: JsonObject) {
        other.forEach { (k, v) -> put(k, v) }
    }
}
