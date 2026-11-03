package com.samosaai.masala.layout

import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.layout.KnownSymbols
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `json_schema` the layout generator asks for (plan Phase 3, lane C).
 *
 * The shape mirrors what [com.samosaai.masala.core.layout.LayoutChecker.parse] decodes
 * (same `"type"` discriminators, same field names), so a valid reply parses with no translation
 * step. Included: header, grid, list, group, dock, skill-widget and generated-widget sections
 * (widget and stack sections hold library refs only, html sections mini-app ids — the prompt
 * lists both, the checker enforces them), app and symbol icons (no overrides), and
 * eight safe actions (no shortcuts, dialing, messaging, modes or raw tools).
 *
 * Strict-mode notes (model check, `docs/internal/model-check.md`): every object lists all of its
 * properties in `required` and sets `additionalProperties: false`. Unions use `anyOf`; if a server
 * rejects the schema, [LayoutGenerator] falls back to `json_object` plus the same checker.
 */
object LayoutSchema {

    /** Symbol names the model may use, as a schema enum — cheap grounding against invented icons. */
    fun schema(symbols: Set<String> = KnownSymbols.promptable): JsonObject {
        val symbolNames = symbols.sorted()
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("schemaVersion") {
                    put("type", "integer")
                    put("const", LayoutDoc.CURRENT_SCHEMA_VERSION)
                }
                putJsonObject("pages") {
                    put("type", "array")
                    putJsonObject("items") { merge(page(symbolNames)) }
                }
            }
            putJsonArray("required") { add("schemaVersion"); add("pages") }
            put("additionalProperties", false)
        }
    }

    private fun page(symbolNames: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("id", string())
            put("title", string())
            putJsonObject("sections") {
                put("type", "array")
                putJsonObject("items") {
                    putJsonArray("anyOf") {
                        addJsonObject { merge(header()) }
                        addJsonObject { merge(grid(symbolNames)) }
                        addJsonObject { merge(list(symbolNames)) }
                        addJsonObject { merge(group(symbolNames)) }
                        addJsonObject { merge(dock(symbolNames)) }
                        addJsonObject { merge(skillWidget()) }
                        addJsonObject { merge(widgetSection()) }
                        addJsonObject { merge(stackSection()) }
                        addJsonObject { merge(htmlSection()) }
                    }
                }
            }
        }
        putJsonArray("required") { add("id"); add("title"); add("sections") }
        put("additionalProperties", false)
    }

    private fun header(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("header"))
            put("id", string())
            put("showClock", bool())
            put("showDate", bool())
        }
        putJsonArray("required") { add("type"); add("id"); add("showClock"); add("showDate") }
        put("additionalProperties", false)
    }

    private fun grid(symbolNames: List<String>): JsonObject = itemSection("grid", symbolNames, withColumns = true, withTitle = true)
    private fun list(symbolNames: List<String>): JsonObject = itemSection("list", symbolNames, withColumns = false, withTitle = true)
    private fun dock(symbolNames: List<String>): JsonObject = itemSection("dock", symbolNames, withColumns = false, withTitle = false)

    private fun group(symbolNames: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("group"))
            put("id", string())
            put("label", string())
            putJsonObject("items") {
                put("type", "array")
                putJsonObject("items") { merge(itemSchema(symbolNames)) }
            }
        }
        putJsonArray("required") { add("type"); add("id"); add("label"); add("items") }
        put("additionalProperties", false)
    }

    private fun itemSection(kind: String, symbolNames: List<String>, withColumns: Boolean, withTitle: Boolean): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                put("type", const(kind))
                put("id", string())
                if (withColumns) put("columns", int())
                if (withTitle) put("title", string())
                putJsonObject("items") {
                    put("type", "array")
                    putJsonObject("items") { merge(itemSchema(symbolNames)) }
                }
            }
            putJsonArray("required") {
                add("type"); add("id")
                if (withColumns) add("columns")
                if (withTitle) add("title")
                add("items")
            }
            put("additionalProperties", false)
        }

    /**
     * A library widget on home: a ref into the widget library, never an inline spec. The model
     * copies refs through from the revise document or picks ids from the library list; the
     * checker rejects unknown ids.
     */
    private fun widgetSection(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("widget"))
            put("id", string())
            putJsonObject("ref") {
                put("type", "object")
                putJsonObject("properties") {
                    put("widgetId", string())
                }
                putJsonArray("required") { add("widgetId") }
                put("additionalProperties", false)
            }
        }
        putJsonArray("required") { add("type"); add("id"); add("ref") }
        put("additionalProperties", false)
    }

    private fun stackSection(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("stack"))
            put("id", string())
            putJsonObject("refs") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("widgetId", string())
                    }
                    putJsonArray("required") { add("widgetId") }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add("type"); add("id"); add("refs") }
        put("additionalProperties", false)
    }

    private fun htmlSection(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("html"))
            put("id", string())
            put("appId", string())
            put("rows", int())
        }
        putJsonArray("required") { add("type"); add("id"); add("appId"); add("rows") }
        put("additionalProperties", false)
    }

    private fun skillWidget(): JsonObject = buildJsonObject {        put("type", "object")
        putJsonObject("properties") {
            put("type", const("skill_widget"))
            put("id", string())
            put("skillId", string())
            putJsonObject("size") {
                put("type", "string")
                putJsonArray("enum") { add("small"); add("medium"); add("large") }
            }
        }
        putJsonArray("required") { add("type"); add("id"); add("skillId"); add("size") }
        put("additionalProperties", false)
    }

    private fun itemSchema(symbolNames: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("id", string())
            put("label", string())
            putJsonObject("icon") {
                putJsonArray("anyOf") {
                    addJsonObject { merge(appIcon()) }
                    addJsonObject { merge(symbolIcon(symbolNames)) }
                }
            }
            putJsonObject("action") {
                putJsonArray("anyOf") {
                    addJsonObject { merge(launchApp()) }
                    addJsonObject { merge(openUrl()) }
                    addJsonObject { merge(setAlarm()) }
                    addJsonObject { merge(startTimer()) }
                    addJsonObject { merge(openDrawer()) }
                    addJsonObject { merge(askMasala()) }
                    addJsonObject { merge(runSkill()) }
                    addJsonObject { merge(openMiniApp()) }
                }
            }
        }
        putJsonArray("required") { add("id"); add("label"); add("icon"); add("action") }
        put("additionalProperties", false)
    }

    private fun appIcon(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("app"))
            put("packageName", string())
            put("activity", string())
            put("user", int())
        }
        putJsonArray("required") { add("type"); add("packageName"); add("activity"); add("user") }
        put("additionalProperties", false)
    }

    private fun symbolIcon(symbolNames: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("type", const("symbol"))
            putJsonObject("name") {
                put("type", "string")
                put("enum", JsonArray(symbolNames.map(::JsonPrimitive)))
            }
        }
        putJsonArray("required") { add("type"); add("name") }
        put("additionalProperties", false)
    }

    private fun launchApp(): JsonObject = action(
        "launch_app", listOf("packageName", "activity", "user")
    ) {
        put("packageName", string())
        put("activity", string())
        put("user", int())
    }

    private fun openUrl(): JsonObject = action("open_url", listOf("url")) {
        put("url", string())
    }

    private fun setAlarm(): JsonObject = action("set_alarm", listOf("hour", "minute", "label")) {
        put("hour", int())
        put("minute", int())
        put("label", string())
    }

    private fun startTimer(): JsonObject = action("start_timer", listOf("seconds", "label")) {
        put("seconds", int())
        put("label", string())
    }

    private fun openDrawer(): JsonObject = action("open_drawer", emptyList()) {}
    private fun askMasala(): JsonObject = action("ask_masala", listOf("prompt")) {
        put("prompt", string())
    }

    private fun runSkill(): JsonObject = action("run_skill", listOf("skillId", "prompt")) {
        put("skillId", string())
        put("prompt", string())
    }

    private fun openMiniApp(): JsonObject = action("open_mini_app", listOf("id")) {
        put("id", string())
    }

    private fun action(kind: String, fieldNames: List<String>, fields: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                put("type", const(kind))
                fields()
            }
            putJsonArray("required") {
                add("type")
                fieldNames.forEach { add(it) }
            }
            put("additionalProperties", false)
        }

    /**
     * Strict-mode validity for this endpoint's validator: every object lists all of its properties
     * in `required` and forbids the rest; no `oneOf`/`allOf`/`not`/`$ref`; the root is an object.
     * `anyOf`, `const` and `enum` are the only unions/constants used.
     */
    fun strictViolations(schema: JsonObject): List<String> {
        val out = mutableListOf<String>()
        fun check(node: JsonObject, path: String) {
            val type = (node["type"] as? JsonPrimitive)?.content
            if (type == "object") {
                val props = node["properties"] as? JsonObject
                val required = node["required"]
                if (props == null) {
                    out += "$path: object without properties"
                } else {
                    val requiredNames = (required as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
                    if (requiredNames == null) {
                        out += "$path: object without a required array"
                    } else {
                        (props.keys - requiredNames).forEach { out += "$path.properties.$it: not in required" }
                        (requiredNames - props.keys).forEach { out += "$path.required.$it: not a property" }
                    }
                    if ((node["additionalProperties"] as? JsonPrimitive)?.content != "false") {
                        out += "$path: additionalProperties is not false"
                    }
                    props.forEach { (name, child) ->
                        if (child is JsonObject) {
                            check(child, "$path.properties.$name")
                        } else {
                            out += "$path.properties.$name: property schema is not an object"
                        }
                    }
                }
            }
            if (node.containsKey("oneOf") || node.containsKey("allOf") || node.containsKey("not") || node.containsKey("\$ref")) {
                out += "$path: unsupported union keyword"
            }
            (node["anyOf"] as? JsonArray)?.forEachIndexed { i, child ->
                if (child is JsonObject) check(child, "$path.anyOf[$i]")
            }
            (node["items"] as? JsonObject)?.let { check(it, "$path.items") }
        }
        check(schema, "$")
        return out
    }

    private fun string(): JsonObject = buildJsonObject { put("type", "string") }
    private fun int(): JsonObject = buildJsonObject { put("type", "integer") }
    private fun bool(): JsonObject = buildJsonObject { put("type", "boolean") }
    private fun const(value: String): JsonObject = buildJsonObject { put("const", value) }

    /** Merges another object's type/properties/required into this builder's object. */
    private fun JsonObjectBuilder.merge(other: JsonObject) {
        other.forEach { (k, v) -> put(k, v) }
    }
}
