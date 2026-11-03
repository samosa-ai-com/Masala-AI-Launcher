package com.samosaai.masala.agent

import com.samosaai.masala.core.agent.ToolInfo
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolSensitivity.READ_ONLY
import com.samosaai.masala.tools.missing
import com.samosaai.masala.tools.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The run's own scratchpad: what it is doing, step by step. In-memory and gone when the run
 * ends — it changes nothing on the phone, so it never asks. One list per agent run (MasalaApp
 * holds it, not the registry, so two runs can't mix their lists up).
 */
object TodoTools {
    val write = ToolDefinition(
        "todowrite",
        "Keep the run's task list: what is done, what is next. Pass the whole list every time " +
            "it changes. The list is the run's own scratchpad and is forgotten when the run ends.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("items") {
                    put("type", "array")
                    put(
                        "description",
                        "The whole list: each item has content, status (pending, in_progress, completed or cancelled) and priority (low, medium or high).",
                    )
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("content") { put("type", "string") }
                            putJsonObject("status") { put("type", "string") }
                            putJsonObject("priority") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add("content")
                            add("status")
                        }
                    }
                }
            }
            putJsonArray("required") { add("items") }
        },
    )

    val definitions: List<ToolDefinition> = listOf(write)
    val infos: List<ToolInfo> = definitions.map { ToolInfo(it.name, READ_ONLY) }
    val names: Set<String> = definitions.mapTo(HashSet()) { it.name }

    private val STATUSES = setOf("pending", "in_progress", "completed", "cancelled")
    private const val MAX_ITEMS = 30

    data class Item(val content: String, val status: String, val priority: String)

    class TodoList {
        private val items = mutableListOf<Item>()

        fun handle(name: String, args: JsonObject): ActionResult {
            if (name != write.name) return ActionResult.error("Unknown list operation '$name'.")
            val raw = args["items"] as? JsonArray
                ?: return ActionResult.error("Missing or invalid required parameter 'items'.")
            if (raw.size > MAX_ITEMS) return ActionResult.error("Keep the list to $MAX_ITEMS items.")
            val parsed = mutableListOf<Item>()
            for (element in raw) {
                val o = element as? JsonObject ?: return ActionResult.error("Each item needs content and status.")
                val content = o.string("content")?.trim()
                    ?: return ActionResult.error("Each item needs content.")
                if (content.isEmpty()) return ActionResult.error("An item's content can't be blank.")
                val status = o.string("status")?.trim()?.lowercase()
                if (status !in STATUSES) {
                    return ActionResult.error("'$status' is not a status. Use pending, in_progress, completed or cancelled.")
                }
                val priority = o.string("priority")?.trim()?.lowercase()?.takeIf {
                    it in setOf("low", "medium", "high")
                } ?: "medium"
                parsed += Item(content.take(200), status!!, priority)
            }
            items.clear()
            items += parsed
            return ActionResult.ok(render())
        }

        fun render(): String {
            if (items.isEmpty()) return "(empty list)"
            return items.mapIndexed { i, item ->
                val mark = when (item.status) {
                    "completed" -> "✓"
                    "in_progress" -> "…"
                    "cancelled" -> "✕"
                    else -> "○"
                }
                "${i + 1}. [$mark ${item.status}] ${item.content} (${item.priority})"
            }.joinToString("\n")
        }
    }
}
