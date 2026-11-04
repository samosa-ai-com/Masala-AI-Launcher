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
 * The `json_schema` the widget generator asks for. The
 * shape mirrors what `WidgetChecker.parse` decodes (same `"type"` discriminators, same field
 * names), so a valid reply parses with no translation step.
 *
 * Everything the home renderer draws is included: native components, `video`/`snapshot` (a
 * stream source id the maker got from list_stream_sources), `web_value` (host approved at
 * placement), and button actions ask_masala, run_skill, start_timer, refresh_widget, run_tool
 * (allowlist only) and play_sound (click only). No rules (the manager's JSON editor adds those).
 * Strict-mode: every object lists its properties in `required` and sets
 * `additionalProperties: false`.
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
        listRows(), gauge(), globe(), row(), column(), video(), snapshot(), webValue(),
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
            put("date", string("ISO yyyy-MM-dd target: the counter shows the days left instead of tapping"))
            put("reset", enumOf("daily", "weekly", "monthly"))
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
            put("reset", enumOf("daily", "weekly", "monthly"))
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
            put("sound", bool("Ring when it reaches zero (default false)"))
            put("autoStartKey", string("Timer key in this widget to start when this one ends (a Pomodoro's break)"))
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
            put("reset", enumOf("daily", "weekly", "monthly"))
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

    private fun globe(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("globe"))
            put("label", string("Small caption under the value"))
            putJsonObject("binding") { merge(binding()) }
            putJsonObject("style") { merge(style()) }
        }
        putJsonArray("required") { add("type") }
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
                    addJsonObject { merge(refreshWidget()) }
                    addJsonObject { merge(runTool()) }
                    addJsonObject { merge(playSound()) }
                    addJsonObject { merge(flip()) }
                }
            }
        }
        putJsonArray("required") { add("type"); add("label"); add("action") }
        put("additionalProperties", false)
    }

    private fun video(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("video"))
            putJsonObject("stream") {
                put("type", "object")
                putJsonObject("properties") {
                    put("sourceId", string("A stream source id from list_stream_sources, never invented"))
                    put("mode", enumOf("live", "snapshot"))
                }
                putJsonArray("required") { add("sourceId") }
                put("additionalProperties", false)
            }
        }
        putJsonArray("required") { add("type"); add("stream") }
        put("additionalProperties", false)
    }

    private fun snapshot(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("snapshot"))
            put("sourceId", string("A stream source id from list_stream_sources, never invented"))
            put("everySeconds", int("Still refresh rate, 2 seconds or more"))
        }
        putJsonArray("required") { add("type"); add("sourceId") }
        put("additionalProperties", false)
    }

    private fun webValue(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("web_value"))
            put("label", string())
            putJsonObject("http") { merge(http()) }
            put("suffix", string("Shown after the value, e.g. € or °C"))
            putJsonObject("style") { merge(style()) }
        }
        putJsonArray("required") { add("type"); add("label"); add("http") }
        put("additionalProperties", false)
    }

    private fun http(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("url", string("http or https, no credentials or tokens"))
            put("jsonPath", string("A $.a.b[0] path into the JSON"))
            put("every", int("Foreground refresh rate, 30 seconds or more"))
            put("background", buildJsonObject { put("type", "boolean") })
        }
        putJsonArray("required") { add("url") }
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
            put("source", enumOf("storage", "next_event", "battery", "media", "steps", "sleep", "weather", "urgent_count", "stream_status", "skill_result", "second_brain", "meeting", "http"))
            put("key", string("Storage key (storage), source id (stream_status), skill id (skill_result), notes|latest (second_brain), status|title|elapsed|speakers|summary (meeting), else missing"))
            putJsonObject("http") { merge(http()) }
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

    private fun refreshWidget(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("refresh_widget"))
            put("widgetId", string("Missing: the holding widget is filled in"))
        }
        putJsonArray("required") { add("type") }
        put("additionalProperties", false)
    }

    private fun runTool(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("run_tool"))
            put("name", enumOf("toggle_torch", "toggle_wifi", "set_timer"))
            // Free-form tool args (set_timer needs seconds): the checker validates the call.
            putJsonObject("args") {
                put("type", "object")
                put("additionalProperties", true)
            }
        }
        putJsonArray("required") { add("type"); add("name") }
        put("additionalProperties", false)
    }

    private fun playSound(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("play_sound"))
            put("kind", enumOf("click", "chime"))
        }
        putJsonArray("required") { add("type") }
        put("additionalProperties", false)
    }

    private fun flip(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("flip"))
            put("key", string("Storage key the result is written to"))
            put("kind", enumOf("coin", "dice"))
        }
        putJsonArray("required") { add("type"); add("key"); add("kind") }
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

    private fun bool(description: String = "") = buildJsonObject {
        put("type", "boolean")
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
