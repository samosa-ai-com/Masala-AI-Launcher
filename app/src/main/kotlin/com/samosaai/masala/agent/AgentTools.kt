package com.samosaai.masala.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.FunctionTool
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolSensitivity.CHANGES_SOMETHING
import com.samosaai.masala.core.tool.ToolSensitivity.READ_ONLY
import com.samosaai.masala.layout.LayoutRepository
import com.samosaai.masala.layout.ModeStore
import com.samosaai.masala.tools.boolean
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
 * The tools the agent adds on top of the Play-safe set: they act on Masala itself (layout, modes,
 * skills) or hand a draft to another app. Kept apart from `MasalaToolDefinitions` on purpose.
 */
object AgentTools {
    private const val MAX_PROBLEMS = 10

    private fun schema(required: List<String>, block: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties", block)
        putJsonArray("required") { required.forEach(::add) }
    }

    private fun JsonObjectBuilder.stringProp(name: String, description: String) =
        putJsonObject(name) {
            put("type", "string")
            put("description", description)
        }

    val editLayout = ToolDefinition(
        "edit_layout",
        "Replace the home screen layout with a new one. Pass the complete layout document as JSON; " +
            "it is checked first and refused with a list of problems if anything is wrong. The previous " +
            "layout stays in the undo history.",
        schema(listOf("layout_json", "label")) {
            stringProp("layout_json", "The whole layout document, as a JSON string.")
            stringProp("label", "A few words describing the change, shown in the undo history.")
        },
    )

    val setMode = ToolDefinition(
        "set_mode",
        "Turn one of the user's modes on or off. Modes are named layouts such as Work or Sleep.",
        schema(listOf("id", "on")) {
            stringProp("id", "The mode's id.")
            putJsonObject("on") {
                put("type", "boolean")
                put("description", "true to turn the mode on, false to turn it off.")
            }
        },
    )

    val runSkill = ToolDefinition(
        "run_skill",
        "Open an installed skill (a persona or helper) and start it on a prompt. Use it when the request " +
            "fits one of the installed skills better than a one-off answer.",
        schema(listOf("skill_id")) {
            stringProp("skill_id", "The id of an installed skill.")
            stringProp("prompt", "What to ask the skill. Optional.")
        },
    )

    val consultExpert = ToolDefinition(
        "consult_expert",
        "Ask a specialist (an expert such as Doctor, Chef or Fitness coach) for their view. They answer from " +
            "their own instructions and tools and cannot see this chat, so include the facts they need. Use it " +
            "when the request is advice in their area, or when their expertise would change your answer. Their " +
            "answer can be long: summarise it for the user in 2–4 short sentences, keeping any safety " +
            "instruction and their disclaimer as given. Pass the consult_id from an earlier result to continue " +
            "with the same expert.",
        schema(listOf("skill_id", "question")) {
            stringProp("skill_id", "The expert's id, from the experts list.")
            stringProp("question", "What to ask them.")
            stringProp("consult_id", "Optional. Continue an earlier consult with this id.")
        },
    )

    val composeMessage = ToolDefinition(
        "compose_message",
        "Open the messaging app with a draft. Masala never sends: the user reads the draft and presses send.",
        schema(listOf("body")) {
            stringProp("to", "Phone number or contact to address the message to. Optional.")
            stringProp("body", "The message text.")
        },
    )

    /**
     * [runSkill] opens the skill sheet and [consult] asks an expert in place; leave either null while
     * there is none, and its tool isn't offered.
     */
    fun create(
        context: Context,
        layouts: LayoutRepository,
        modes: ModeStore,
        runSkill: ((skillId: String, prompt: String?) -> ActionResult)? = null,
        consult: (suspend (skillId: String, question: String, consultId: String?) -> ActionResult)? = null,
    ): List<Tool> {
        val app = context.applicationContext
        return listOfNotNull(
            FunctionTool(editLayout, CHANGES_SOMETHING) { args ->
                val text = args.string("layout_json") ?: return@FunctionTool missing("layout_json")
                val label = args.string("label") ?: "Edited by Masala"
                val parsed = LayoutChecker.parse(text)
                val layout = parsed.layout ?: return@FunctionTool ActionResult.error(problems(parsed.problems))
                val problems = layouts.apply(label, layout)
                if (problems.isEmpty()) ActionResult.ok("Layout updated: $label. The user can undo it.") else ActionResult.error(problems(problems))
            },
            FunctionTool(setMode, CHANGES_SOMETHING) { args ->
                val id = args.string("id") ?: return@FunctionTool missing("id")
                val on = args.boolean("on") ?: return@FunctionTool missing("on")
                if (modes.patches.value.none { it.id == id }) {
                    return@FunctionTool ActionResult.error("There is no mode '$id'. Modes: ${modes.patches.value.joinToString { it.id }.ifEmpty { "none" }}.")
                }
                modes.setActive(id, on)
                ActionResult.ok("Mode '$id' is ${if (on) "on" else "off"}.")
            },
            runSkill?.let { start ->
                FunctionTool(this.runSkill, READ_ONLY, opensScreen = true) { args ->
                    start(args.string("skill_id") ?: return@FunctionTool missing("skill_id"), args.string("prompt"))
                }
            },
            consult?.let { ask ->
                // It reads the conversation, so it is never offered to a skill run's narrower scope.
                FunctionTool(this.consultExpert, READ_ONLY, readsPrivateData = true) { args ->
                    ask(
                        args.string("skill_id") ?: return@FunctionTool missing("skill_id"),
                        args.string("question") ?: return@FunctionTool missing("question"),
                        args.string("consult_id"),
                    )
                }
            },
            FunctionTool(composeMessage, READ_ONLY, opensScreen = true) { args ->
                val body = args.string("body") ?: return@FunctionTool missing("body")
                val to = args.string("to").orEmpty()
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(to)}"))
                    .putExtra("sms_body", body)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    app.startActivity(intent)
                    ActionResult.ok("Opened the messaging app with a draft. The user must press send themselves.")
                } catch (e: ActivityNotFoundException) {
                    ActionResult.error("No messaging app is available on this device.")
                }
            },
        )
    }

    private fun problems(list: List<LayoutChecker.Problem>) =
        "The layout was not applied:\n" + list.take(MAX_PROBLEMS).joinToString("\n") +
            if (list.size > MAX_PROBLEMS) "\n…and ${list.size - MAX_PROBLEMS} more." else ""
}
