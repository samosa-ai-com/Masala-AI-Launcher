package com.samosaai.masala.skills

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.FunctionTool
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolSensitivity.READ_ONLY
import com.samosaai.masala.tools.missing
import com.samosaai.masala.tools.string
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Finds installed skills by words in their id, title, description or target packages. The prompt
 * lists at most 30 one-liners, so this is how the model looks further; it never returns full
 * instructions (those come from `run_skill`). Excluded from unattended runs at the call site.
 */
object SkillSearchTool {
    private const val MAX_RESULTS = 30

    val definition = ToolDefinition(
        "search_skills",
        "Find installed skills (personas and helpers) by keywords, e.g. 'code', 'food' or a package " +
            "name. Use it when the request fits a skill better than a one-off answer but none of the " +
            "listed skills fits. Returns matching skills as 'id: what it does' lines; start one with " +
            "run_skill. Never returns the skill's full instructions.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Words to find, e.g. 'trip' or 'com.example.app'.")
                }
            }
            putJsonArray("required") { add("query") }
        },
    )

    /**
     * @param search skill lookup, `SkillRegistry::searchSkills` in production.
     * @param hiddenIds experts the user hid: never surfaced, mirroring the prompt index.
     */
    fun create(
        search: (String) -> List<MasalaSkill>,
        hiddenIds: () -> Set<String> = { emptySet() },
    ): Tool = FunctionTool(definition, READ_ONLY) { args ->
        val query = args.string("query") ?: return@FunctionTool missing("query")
        val hidden = hiddenIds()
        val hits = search(query)
            .filterNot { it.persona != null && it.id in hidden }
            .take(MAX_RESULTS)
        if (hits.isEmpty()) {
            ActionResult.ok("No installed skill matches '$query'. Answer directly, or ask the user what kind of helper they had in mind.")
        } else {
            ActionResult.ok(
                hits.joinToString("\n") { "• ${it.id}: ${it.description.ifBlank { it.title }}" } +
                    "\nStart one with run_skill (experts: consult_expert)."
            )
        }
    }
}
