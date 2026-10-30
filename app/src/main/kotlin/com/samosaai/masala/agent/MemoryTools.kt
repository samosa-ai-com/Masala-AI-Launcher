package com.samosaai.masala.agent

import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.FunctionTool
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolSensitivity.CHANGES_SOMETHING
import com.samosaai.masala.core.tool.ToolSensitivity.DESTRUCTIVE
import com.samosaai.masala.core.tool.ToolSensitivity.READ_ONLY
import com.samosaai.masala.secondbrain.MemoryProtocol
import com.samosaai.masala.secondbrain.SecondBrainRepository
import com.samosaai.masala.secondbrain.SecondBrainStore
import com.samosaai.masala.tools.boolean
import com.samosaai.masala.tools.int
import com.samosaai.masala.tools.missing
import com.samosaai.masala.tools.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Second Brain tools (plan `docs/plans/second-brain-robust.md`, S3). Reads run freely. Writes and edits
 * are CHANGES_SOMETHING, so unattended runs never get them, but they apply without a sheet: the user sees a
 * "Remembered" card with Undo (D1). Rewriting a dated note and deleting anything still ask.
 */
object MemoryTools {
    private fun schema(required: List<String>, block: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties", block)
        putJsonArray("required") { required.forEach(::add) }
    }

    private fun JsonObjectBuilder.prop(name: String, description: String, type: String = "string") = putJsonObject(name) {
        put("type", type)
        put("description", description)
    }

    private const val PATH_HELP = "Vault path, e.g. people/asha-sharma.md, notes/2026-10-29-standup.md, areas/health.md or MEMORY.md."

    val read = ToolDefinition(
        "memory_read",
        "Read the user's Second Brain. Pass path to open one file (paths come from MEMORY.md, links and " +
            "memory_search), path \"PROTOCOL\" for the rules to follow before writing, or query to get the " +
            "three entries that best match some words.",
        schema(emptyList()) {
            prop("path", PATH_HELP + " Or PROTOCOL.")
            prop("query", "Words to look for, when you don't know the path.")
        },
    )

    val search = ToolDefinition(
        "memory_search",
        "Search every Second Brain file for text, like grep: returns path:line: text for matching lines. " +
            "Run it for a name before creating a person, area or note, so an existing file is updated instead.",
        schema(listOf("query")) {
            prop("query", "Text to find (case-insensitive), e.g. a name.")
            prop("limit", "Most lines to return (default 20).", "integer")
        },
    )

    val write = ToolDefinition(
        "memory_write",
        "Write a whole Second Brain file, or append to it. Records in areas/, people/ and notes/ need frontmatter " +
            "(title, area, type, updated) and a body; read PROTOCOL first. Applies at once and the user can undo " +
            "it; say briefly what you remembered. Only store what the user told you or asked to keep.",
        schema(listOf("path", "content")) {
            prop("path", PATH_HELP)
            prop("content", "The full file (frontmatter and body), or the text to add when append is true.")
            prop("append", "Add content to the end instead of replacing the file (the only way to add to a dated note).", "boolean")
            prop("allow_duplicate", "Create the file even though a record with the same name or title exists.", "boolean")
        },
    )

    val edit = ToolDefinition(
        "memory_edit",
        "Replace exact text in a Second Brain file, e.g. update one fact on a person card. old_text must match " +
            "the file exactly (read it first). The result must still be a valid record. Applies at once with " +
            "Undo; changing a dated note asks the user.",
        schema(listOf("path", "old_text", "new_text")) {
            prop("path", PATH_HELP)
            prop("old_text", "Text to replace, copied exactly from the file.")
            prop("new_text", "The replacement.")
            prop("replace_all", "Replace every occurrence (default: old_text must occur once).", "boolean")
        },
    )

    val delete = ToolDefinition(
        "memory_delete",
        "Delete a file from areas/, people/, notes/ or raw/. Asks the user first. Remove links to it afterwards.",
        schema(listOf("path")) { prop("path", PATH_HELP) },
    )

    fun create(secondBrain: SecondBrainRepository): List<Tool> = listOf(
        FunctionTool(read, READ_ONLY, readsPrivateData = true) { args ->
            val path = args.string("path")?.takeIf { it.isNotBlank() }
            val query = args.string("query")?.takeIf { it.isNotBlank() }
            when {
                path != null -> {
                    val text = secondBrain.readPath(path)
                        ?: return@FunctionTool ActionResult.error("No readable Second Brain file at '$path'. Use memory_search to find the path.")
                    val tail = if (text.truncated) "\n… (truncated)" else ""
                    ActionResult.ok(if (path.equals(MemoryProtocol.PATH, ignoreCase = true)) text.text else "# $path\n${text.text}$tail")
                }
                query != null -> {
                    val entries = secondBrain.read(query)
                    if (entries.isEmpty()) ActionResult.ok("No Second Brain entry matches '$query'.")
                    else ActionResult.ok(entries.joinToString("\n\n") { "## ${secondBrain.idOf(it)}\n${it.content.trim()}" })
                }
                else -> missing("path")
            }
        },
        FunctionTool(search, READ_ONLY, readsPrivateData = true) { args ->
            val query = args.string("query")?.takeIf { it.isNotBlank() } ?: return@FunctionTool missing("query")
            val hits = secondBrain.search(query, (args.int("limit") ?: 20).coerceIn(1, 100))
            ActionResult.ok(if (hits.isEmpty()) "Nothing in the Second Brain mentions '$query'." else hits.joinToString("\n"))
        },
        FunctionTool(write, CHANGES_SOMETHING, confirmWhen = { false }) { args ->
            val path = args.string("path") ?: return@FunctionTool missing("path")
            val content = args.string("content") ?: return@FunctionTool missing("content")
            result(secondBrain.write(path, content, args.boolean("append") ?: false, args.boolean("allow_duplicate") ?: false))
        },
        FunctionTool(edit, CHANGES_SOMETHING, confirmWhen = { args -> args.path()?.let(secondBrain::isDatedNote) ?: false }) { args ->
            val path = args.string("path") ?: return@FunctionTool missing("path")
            val old = args.string("old_text") ?: return@FunctionTool missing("old_text")
            val new = args.string("new_text") ?: return@FunctionTool missing("new_text")
            result(secondBrain.edit(path, old, new, args.boolean("replace_all") ?: false))
        },
        FunctionTool(delete, DESTRUCTIVE) { args ->
            val path = args.string("path") ?: return@FunctionTool missing("path")
            result(secondBrain.remove(path))
        },
    )

    private fun JsonObject.path(): String? = string("path")

    private fun result(outcome: SecondBrainStore.WriteOutcome): ActionResult =
        if (outcome.ok) {
            ActionResult.ok(if (outcome.created) "Created ${outcome.id}." else "Saved ${outcome.id}.")
        } else {
            ActionResult.error(
                "Not saved:\n" + outcome.errors.joinToString("\n") { "- $it" } +
                    "\nFix it and retry (rules: memory_read path PROTOCOL).",
            )
        }
}
