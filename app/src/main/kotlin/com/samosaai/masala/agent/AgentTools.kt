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
import com.samosaai.masala.tools.int
import com.samosaai.masala.tools.missing
import com.samosaai.masala.tools.string
import com.samosaai.masala.tools.stringList
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

    /** What `about_masala` answers: the app's own features, in the user's terms. */
    private const val ABOUT_MASALA =
        "Masala is an Android home screen with an assistant built in. " +
            "Home holds apps, folders and widgets; the drawer lists everything with search and an A-Z scrubber; " +
            "a long press on anything opens its menu (hide, icon, edit with Masala). " +
            "Chat (the orb or the type field beside the mic) acts on the phone with tools, or consults an expert " +
            "persona — doctor, chef, coach and more under Settings → Experts. " +
            "Skills under Settings → Skills add helpers; some build mini apps that pin to home. " +
            "The Second Brain (Settings → Second Brain) remembers people, notes and routines. " +
            "Scheduled tasks (Settings → Scheduled tasks) run prompts on a schedule. " +
            "Hidden apps live under Settings → Privacy & data, optionally behind a PIN. " +
            "Permissions, each asked when first needed: Calendar (read/write) for the calendar tools, " +
            "Contacts for contact lookup, Location for weather and location, and Usage access plus " +
            "Notification access for app stats and media controls."

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
        "Consult a specialist (a persona such as Doctor or Chef) and get its answer. It runs on its own " +
            "instructions and tools and cannot see this chat, so include the facts it needs. Use it when the " +
            "expert's knowledge would change what you tell the user; you decide what, if anything, the user " +
            "needs to hear. Returns the answer, this consult's id and the consults in this chat. Pass consult_id " +
            "from an earlier result to continue with the same expert.",
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

    val sleep = ToolDefinition(
        "sleep",
        "Pause the run for a few seconds, e.g. waiting for something else to finish. " +
            "Resolves at once when the run is cancelled.",
        schema(emptyList()) {
            putJsonObject("seconds") {
                put("type", "integer")
                put("description", "Seconds to wait (1-300).")
            }
        },
    )

    val question = ToolDefinition(
        "question",
        "Ask the user something mid-run, with up to 10 options. Use it when their answer changes " +
            "what you do next; otherwise decide yourself. The user can also answer in their own " +
            "words, or dismiss the question — then carry on without the answer. Never ask what you " +
            "can look up with a tool.",
        schema(listOf("question")) {
            stringProp("question", "The question, in the user's words.")
            putJsonObject("options") {
                put("type", "array")
                put("description", "Up to 10 short options. Empty lets the user answer freely.")
                putJsonObject("items") { put("type", "string") }
            }
        },
    )

    val about = ToolDefinition(
        "about_masala",
        "What Masala is and does: the launcher's features, where its settings live, and which " +
            "permissions each part needs. Answer questions about the app itself from this.",
        schema(emptyList()) {},
    )

    private const val MAX_SLEEP_SECONDS = 300

    /**
     * [runSkill] opens the skill sheet and [consult] asks an expert in place; leave either null while
     * there is none, and its tool isn't offered. [ask] answers a `question` call through the
     * question sheet; without it the tool isn't offered either.
     */
    fun create(
        context: Context,
        layouts: LayoutRepository,
        modes: ModeStore,
        runSkill: ((skillId: String, prompt: String?) -> ActionResult)? = null,
        consult: (suspend (skillId: String, question: String, consultId: String?) -> ActionResult)? = null,
        ask: (suspend (question: String, options: List<String>) -> ActionResult)? = null,
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
            FunctionTool(sleep, READ_ONLY) { args ->
                val seconds = (args.int("seconds") ?: 0).coerceIn(0, MAX_SLEEP_SECONDS)
                if (seconds < 1) return@FunctionTool ActionResult.error("Wait 1-$MAX_SLEEP_SECONDS seconds.")
                kotlinx.coroutines.delay(seconds * 1000L)
                ActionResult.ok("Waited $seconds second(s).")
            },
            ask?.let { answer ->
                FunctionTool(question, READ_ONLY, opensScreen = true) { args ->
                    val options = args.stringList("options").orEmpty().map { it.trim() }
                        .filter { it.isNotEmpty() }.take(com.samosaai.masala.actions.QuestionGate.MAX_OPTIONS)
                    answer(
                        args.string("question") ?: return@FunctionTool missing("question"),
                        options,
                    )
                }
            },
            FunctionTool(about, READ_ONLY) {
                ActionResult.ok(ABOUT_MASALA)
            },
        )
    }

    private fun problems(list: List<LayoutChecker.Problem>) =
        "The layout was not applied:\n" + list.take(MAX_PROBLEMS).joinToString("\n") +
            if (list.size > MAX_PROBLEMS) "\n…and ${list.size - MAX_PROBLEMS} more." else ""
}
