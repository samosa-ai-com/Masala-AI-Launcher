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
 * next to the private prototype's. Library writes run unattended — a made or revised widget only lands in the
 * versioned library, and the chat shows it as a preview card. The gate asks on what the user can
 * see or lose: home placement, removal, deletion, and new stream sources (plan W3 exit: the gate
 * shows on placement).
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
        suspend fun probe(spec: WidgetSpec): List<LayoutChecker.Problem> {
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
            FunctionTool(Defs.createWidget, CHANGES_SOMETHING, confirmWhen = { false }) {
                val (spec, error) = specFromArgs(it)
                if (spec == null) return@FunctionTool ActionResult.error(error!!)
                findSimilarWidget(host().widgetLibrary.current(), spec)?.let { dup ->
                    val n = host().layouts.widgetPlacements(dup.id).size
                    val where = if (n == 0) "not on home" else "on home ×$n"
                    return@FunctionTool ActionResult.error(
                        "A similar widget already exists: '${dup.id}' (${dup.title ?: dup.id}, $where). " +
                            "Offer to use it (place_widget), revise it (update_widget), or confirm the new one is different before saving.",
                    )
                }
                val id = freeId(spec.id, spec.title)
                val final = spec.copy(id = id)
                host().widgetLibrary.save(final)
                ActionResult.ok(
                    "Widget '$id' created${if (id != spec.id) " (renamed from '${spec.id}')" else ""}. Nothing is on home until place_widget, which asks the user first.",
                    listOf(Produced("widget", final.title ?: id, id)),
                )
            },
            FunctionTool(Defs.updateWidget, CHANGES_SOMETHING, confirmWhen = { false }) {
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
            FunctionTool(
                Defs.placeWidget,
                CHANGES_SOMETHING,
                // The sheet names what placing approves: the gate always fires, so approved=true
                // only ever confirms a conversation the user already saw the hosts for.
                confirmSummary = { args ->
                    val id = args.string("id")
                    val fetches = id?.let { host().widgetLibrary.current()[it] }
                        ?.let { HttpApproval.fetchesOf(it) }.orEmpty().distinctBy { it.host }
                    when {
                        id == null -> "Put a widget on home"
                        fetches.isEmpty() -> "Put \"$id\" on home"
                        else -> "Put \"$id\" on home (fetches ${fetches.joinToString(", ") { "${it.host} every ${it.every}s" }})"
                    }
                },
            ) {
                val id = it.string("id") ?: return@FunctionTool missing("id")
                val spec = host().widgetLibrary.current()[id]
                    ?: return@FunctionTool ActionResult.error("No widget '$id'. Call list_widgets to see the library.")
                val pending = HttpApproval.needingApproval(spec, host().httpApproval.approved(id))
                if (pending.isNotEmpty() && it.string("approved")?.trim()?.lowercase() != "true") {
                    return@FunctionTool ActionResult.error(
                        "This widget fetches:\n${HttpApproval.describe(pending)}\nTell the user, and call again with approved=true once they agree.",
                    )
                }
                val placed = host().layouts.placeWidget(
                    "Place widget $id",
                    id,
                    rowsOverride = it.string("rows")?.toIntOrNull(),
                    pageId = it.string("page"),
                )
                if (placed.problems.isNotEmpty()) return@FunctionTool ActionResult.error(placed.problems.joinToString("\n"))
                if (placed.alreadyPlaced) {
                    return@FunctionTool ActionResult.ok("Widget '$id' is already on ${host().layouts.pageLabel(placed.pageId ?: id)}.")
                }
                host().httpApproval.approve(id, pending.map { it.host })
                val fetchNote = pending.takeIf { it.isNotEmpty() }?.let { "\nApproved fetching:\n${HttpApproval.describe(it)}" }.orEmpty()
                val where = host().layouts.pageLabel(placed.pageId ?: id)
                ActionResult.ok("Widget '$id' is on $where.$fetchNote")
            },
            FunctionTool(Defs.removeWidget, CHANGES_SOMETHING) {
                val id = it.string("id") ?: return@FunctionTool missing("id")
                val problems = host().layouts.removeWidgetRefs("Remove widget $id", id)
                if (problems.isNotEmpty()) return@FunctionTool ActionResult.error(problems.joinToString("\n"))
                ActionResult.ok("Widget '$id' is off home, still in the library.")
            },
            FunctionTool(Defs.deleteWidget, DESTRUCTIVE) {
                val id = it.string("id") ?: return@FunctionTool missing("id")
                // Strip first: deleting the library before its refs bricks later edits as
                // "Unknown widget", and undo can't bring the library entry back.
                val problems = host().layouts.removeWidgetRefs("Delete widget $id", id)
                if (problems.isNotEmpty()) return@FunctionTool ActionResult.error(problems.joinToString("\n"))
                host().widgetLibrary.delete(id)
                host().layouts.removeWidgetRefs("Delete widget $id", id)
                host().httpValues.clearWidget(id)
                host().httpApproval.clearWidget(id)
                ActionResult.ok("Widget '$id' is deleted from the library and off home everywhere.")
            },
            FunctionTool(Defs.listStreamSources, READ_ONLY) {
                val rows = host().streamSources.sources.value
                if (rows.isEmpty()) {
                    return@FunctionTool ActionResult.ok("No stream sources yet. Add one from Settings → Widgets → Cameras & streams, or with add_stream_source.")
                }
                ActionResult.ok(rows.sortedBy { it.name }.joinToString("\n") {
                    "${it.id}: ${it.name} (${it.kind.name.lowercase()}${if (it.hasCredentials) ", with login" else ""})"
                })
            },
            FunctionTool(Defs.addStreamSource, CHANGES_SOMETHING) {
                val name = it.string("name") ?: return@FunctionTool missing("name")
                val url = it.string("url") ?: return@FunctionTool missing("url")
                if (it.string("password") != null) {
                    return@FunctionTool ActionResult.error("Never put a password in chat. Call again without it; the user types it into a sheet on screen.")
                }
                val kind = it.string("kind")?.let(StreamKind::parse) ?: guessKind(url)
                    ?: return@FunctionTool ActionResult.error("Couldn't tell the kind from that URL. Pass kind: rtsp, hls, dash, progressive, mjpeg or snapshot.")
                val username = it.string("username")?.trim().orEmpty()
                val password = if (username.isEmpty()) null else when (val answer = host().questionGate.ask("Password for '$username' on '$name'?", listOf("No password needed", "I'll add it in Settings instead"))) {
                    is com.samosaai.masala.actions.QuestionGate.Answer.Custom -> answer.text
                    else -> null
                }
                val (id, message) = host().streamSources.add(name, kind, url, null, username.ifEmpty { null }, password, if (kind == StreamKind.RTSP) "tcp" else null)
                if (id == null) return@FunctionTool ActionResult.error(message ?: "Couldn't save the source.")
                ActionResult.ok("Stream source '$id' added ($name, ${kind.name.lowercase()}). Run test_stream to check it before placing a widget.")
            },
            FunctionTool(Defs.testStream, READ_ONLY) {
                val id = it.string("id") ?: return@FunctionTool missing("id")
                val source = host().streamSources.current()[id]
                    ?: return@FunctionTool ActionResult.error("No stream source '$id'. Call list_stream_sources to see them.")
                val streamUrl = host().streamSources.urlWithCredentials(id)
                    ?: return@FunctionTool ActionResult.error("The login for '$id' no longer opens. Re-add it from Settings → Widgets → Cameras & streams.")
                val result = StreamTest.test(app as Context, source.kind, streamUrl, host().streamSources.snapshotUrlWithCredentials(id))
                if (result.ok) ActionResult.ok("${source.name}: ${result.detail}") else ActionResult.error("${source.name}: ${result.detail}")
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

    /**
     * Titles are the dedupe key: storage keys and ids are safely reused across widgets (state
     * is per widget id), so only a matching title blocks with an offer to reuse it. Bare id
     * collisions fall through to numeric suffixes at the call site.
     */
    internal fun normWidgetName(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    internal fun findSimilarWidget(lib: Map<String, WidgetSpec>, spec: WidgetSpec): WidgetSpec? {
        val wantName = normWidgetName(spec.title ?: return null)
        if (wantName.isEmpty()) return null
        return lib.values.firstOrNull { normWidgetName(it.title ?: it.id) == wantName }
    }

    internal fun guessKind(url: String): StreamKind? {
        val lower = url.trim().lowercase()
        return when {
            lower.startsWith("rtsp") -> StreamKind.RTSP
            ".m3u8" in lower -> StreamKind.HLS
            ".mpd" in lower -> StreamKind.DASH
            ".mp4" in lower || ".webm" in lower -> StreamKind.PROGRESSIVE
            "mjpeg" in lower || "mjpg" in lower || "multipart" in lower -> StreamKind.MJPEG
            ".jpg" in lower || ".jpeg" in lower || "snapshot" in lower -> StreamKind.SNAPSHOT
            else -> null
        }
    }

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
- button {label, action}: action is ask_masala {prompt}, run_skill {skillId}, start_timer {seconds}, refresh_widget (reloads this widget's web values in place; no id needed), run_tool (only toggle_torch, toggle_wifi, set_timer), or play_sound (click only)
- image {icon {type symbol, name}}: a built-in symbol name like star, timer, checklist
- list {binding, maxRows}: rows from a binding, at most 8
- gauge {label, binding, max}: a ring; style like text
- row/column {children[]}: grouping only, leaves inside, nested at most 2 deep
Bindings {"source", "key"?}: source storage reads the widget's own key; next_event, battery, steps,
sleep, weather and urgent_count read the phone (no key). A web_value reads one JSON value over http:
{"source":"http","url","jsonPath"?,"every"?} (http or https, no credentials or tokens in the url;
every is 30 seconds or more, 900 for a price). Its host is approved when the widget is placed:
place_widget names hosts it hasn't seen, and you call it again with approved=true once the user
agrees. A video or snapshot shows a stream source by id from list_stream_sources (run test_stream
first); never invent a source. One request makes exactly one widget: if the live URL or source
isn't known, ask the user for it instead of building a second widget.
Limits: ids and storage keys are slugs; one component owns each storage key; tint is a palette name
(accent, blue, green, amber, red), never a free color.
"""
}
