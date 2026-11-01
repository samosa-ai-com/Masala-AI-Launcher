package com.samosaai.masala.widgets

import android.content.Context
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.FunctionTool
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolSensitivity.CHANGES_SOMETHING
import com.samosaai.masala.core.tool.ToolSensitivity.DESTRUCTIVE
import com.samosaai.masala.core.tool.ToolSensitivity.READ_ONLY
import com.samosaai.masala.core.widgets.WidgetChecker
import com.samosaai.masala.layout.LlmChatCaller
import com.samosaai.masala.layout.PromptContext
import com.samosaai.masala.tools.MasalaToolDefinitions as Defs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Widget Maker's tools (plan `docs/plans/widget-maker.md` §7.2), registered in `MasalaApp`
 * next to the private prototype's. Read-only ones run unattended; the rest go through the confirmation gate.
 * Every made or changed widget returns `Produced("widget", …)`, which the chat draws as a live
 * preview card with Add to home / Open in manager.
 */
object WidgetTools {

    fun create(context: Context): List<Tool> {
        val app = context.applicationContext
        fun host() = app as com.samosaai.masala.MasalaApp
        fun generator() = WidgetGenerator(LlmChatCaller(host().aiSettings.chatConfig().client(app as Context)))

        fun requestBase() = host().layouts.base.value
        fun homeSummary(): String {
            val base = requestBase()
            return if (base == null) "(no home layout yet)" else PromptContext.summarizeLayout(base)
        }
        fun skillIds(): Set<String> = host().skills.getAllSkills().map { it.id }.toSet()

        /** The probe: the spec placed in a scratch layout must check clean against the phone. */
        fun probe(spec: WidgetSpec): List<LayoutChecker.Problem> {
            val env = host().layouts.environment().copy(widgets = host().widgetLibrary.current() + (spec.id to spec))
            val scratch = LayoutDoc(pages = listOf(Page("probe", sections = listOf(WidgetSection("probe", ref = WidgetRef(spec.id))))))
            return LayoutChecker.check(scratch, env)
        }

        suspend fun specFromArgs(args: JsonObject, revise: WidgetSpec? = null): Pair<WidgetSpec?, String?> {
            fun fail(message: String): Pair<WidgetSpec?, String?> = Pair(null, message)
            val specText = args.string("spec")
            val sentence = args.string("sentence")?.trim().orEmpty()
            if ((specText == null) == sentence.isEmpty()) {
                return fail("Pass exactly one of 'spec' (full WidgetSpec JSON) or 'sentence' (what to build).")
            }
            if (specText != null) {
                val parsed = WidgetChecker.parse(specText)
                val spec = parsed.widget ?: return fail("That spec doesn't parse:\n" + parsed.problems.joinToString("\n"))
                if (revise != null && spec.id != revise.id) {
                    return fail("A revision keeps the id '${revise.id}': got '${spec.id}'.")
                }
                val problems = WidgetChecker.check(spec, host().layouts.environment()) + slugProblems(spec)
                if (problems.isNotEmpty()) return fail("That spec has problems:\n" + problems.joinToString("\n"))
                return Pair(spec, null)
            }
            val request = WidgetGenerator.Request(userText = sentence, homeSummary = homeSummary(), skillIds = skillIds(), revise = revise)
            return when (val result = generator().generate(request, ::probe)) {
                is WidgetGenerator.Result.Generated -> Pair(result.spec, null)
                is WidgetGenerator.Result.Rejected -> fail("The maker couldn't write a clean spec:\n" + result.problems.joinToString("\n"))
                is WidgetGenerator.Result.Failed -> fail("The maker couldn't reach the model: ${result.message}")
            }
        }

        fun freeId(want: String, title: String?): String {
            var base = want.trim().ifEmpty { title?.slug() ?: "widget" }
            if (!WidgetIdRule.test(base)) base = "widget"
            if (base.length > 60) base = base.take(60)
            val known = host().widgetLibrary.current().keys
            if (base !in known) return base
            var n = 2
            while ("$base-$n" in known) n++
            return "$base-$n"
        }

        return listOf(
            FunctionTool(Defs.listWidgets, READ_ONLY) {
                val lib = host().widgetLibrary.current()
                if (lib.isEmpty()) return@FunctionTool ActionResult.ok("No widgets in the library yet.")
                ActionResult.ok(lib.values.sortedBy { it.title ?: it.id }.joinToString("\n") { spec ->
                    val n = host().layouts.widgetPlacements(spec.id).size
                    "${spec.id}: ${spec.title ?: spec.id} (${if (n == 0) "not on home" else "on home ×$n"})"
                })
            },
            FunctionTool(Defs.readWidget, READ_ONLY) {
                val id = it.string("id") ?: return@FunctionTool missing("id")
                val spec = host().widgetLibrary.current()[id]
                    ?: return@FunctionTool ActionResult.error("No widget '$id'. Call list_widgets to see the library.")
                ActionResult.ok(MasalaJson.strict.encodeToString(WidgetSpec.serializer(), spec))
            },
            FunctionTool(Defs.widgetSchema, READ_ONLY) { ActionResult.ok(SCHEMA_TEXT) },
            FunctionTool(Defs.createWidget, CHANGES_SOMETHING) {
                val (spec, error) = specFromArgs(it)
                if (spec == null) return@FunctionTool ActionResult.error(error!!)
                val id = freeId(spec.id, spec.title)
                val final = spec.copy(id = id)
                host().widgetLibrary.save(final)
                ActionResult.ok(
                    "Widget '$id' created${if (id != spec.id) " (renamed from '${spec.id}')" else ""}. Nothing is on home until place_widget, which asks the user first.",
                    listOf(Produced("widget", final.title ?: id, id)),
                )
            },
            FunctionTool(Defs.updateWidget, CHANGES_SOMETHING) {
                val id = it.string("id") ?: return@FunctionTool missing("id")
                val current = host().widgetLibrary.current()[id]
                    ?: return@FunctionTool ActionResult.error("No widget '$id'. Call list_widgets to see the library.")
                val (spec, error) = specFromArgs(it, current)
                if (spec == null) return@FunctionTool ActionResult.error(error!!)
                val final = spec.copy(id = id)
                host().widgetLibrary.save(final)
                ActionResult.ok(
                    "Widget '$id' updated. The new look is on home already; moving it is place_widget's job.",
                    listOf(Produced("widget", final.title ?: id, id)),
                )
            },
            FunctionTool(Defs.placeWidget, CHANGES_SOMETHING) {
                val id = it.string("id") ?: return@FunctionTool missing("id")
                if (id !in host().widgetLibrary.current()) {
                    return@FunctionTool ActionResult.error("No widget '$id'. Call list_widgets to see the library.")
                }
                val problems = host().layouts.placeWidget(
                    "Place widget $id",
                    id,
                    rowsOverride = it.string("rows")?.toIntOrNull(),
                    pageId = it.string("page"),
                )
                if (problems.isNotEmpty()) return@FunctionTool ActionResult.error(problems.joinToString("\n"))
                ActionResult.ok("Widget '$id' is on home.")
            },
            FunctionTool(Defs.removeWidget, CHANGES_SOMETHING) {
                val id = it.string("id") ?: return@FunctionTool missing("id")
                val problems = host().layouts.removeWidgetRefs("Remove widget $id", id)
                if (problems.isNotEmpty()) return@FunctionTool ActionResult.error(problems.joinToString("\n"))
                ActionResult.ok("Widget '$id' is off home, still in the library.")
            },
            FunctionTool(Defs.deleteWidget, DESTRUCTIVE) {
                val id = it.string("id") ?: return@FunctionTool missing("id")
                host().widgetLibrary.delete(id)
                host().layouts.removeWidgetRefs("Delete widget $id", id)
                ActionResult.ok("Widget '$id' is deleted from the library and off home everywhere.")
            },
        )
    }

    private fun slugProblems(spec: WidgetSpec): List<LayoutChecker.Problem> =
        if (WidgetIdRule.test(spec.id)) emptyList()
        else listOf(LayoutChecker.Problem("$.id", "id '${spec.id}' must be a short slug: lowercase letters, digits, _ and -"))

    private fun String.slug(): String {
        val clean = lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
            .replace(Regex("_+"), "_").take(60)
        return if (clean.firstOrNull()?.isLetter() == true) clean else "widget"
    }

    private fun missing(param: String) = ActionResult.error("Missing or invalid required parameter '$param'.")

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString || it.content.isNotEmpty() }?.content

    const val SCHEMA_TEXT: String = """
A widget is a WidgetSpec: id (short slug), title, components (at most 12), size {min, default, max} in rows.
Components (each has "type"):
- text: fixed "text", or a "binding" for a live value; "style" {tint, emphasis normal|large, align start|center|end}
- counter {key, label, step, goal?}: a tap adds step; the key is owned by this counter
- checklist {key, items[]}: at most 20 items; the key is owned by this list
- timer {key, label, seconds}: counts down from Start; the key holds its deadline
- progress {label, binding, max}: a bar; style like text
- chart {binding, kind bar|line, days}: comma-separated values in storage, oldest first, at most 31
- button {label, action}: action is ask_masala {prompt}, run_skill {skillId}, or start_timer {seconds}
- image {icon {type symbol, name}}: a built-in symbol name like star, timer, checklist
- list {binding, maxRows}: rows from a binding, at most 8
- gauge {label, binding, max}: a ring; style like text
- row/column {children[]}: grouping only, leaves inside, nested at most 2 deep
Bindings {"source", "key"?}: source storage reads the widget's own key; next_event, battery, steps,
sleep, weather and urgent_count read the phone (no key). No video, snapshot, web_value or http
bindings yet: those arrive later, so never put them in a generated spec.
Limits: ids and storage keys are slugs; one component owns each storage key; tint is a palette name
(accent, blue, green, amber, red), never a free color.
"""
}
