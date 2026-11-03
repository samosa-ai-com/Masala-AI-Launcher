package com.samosaai.masala.core.widgets

import com.samosaai.masala.core.contract.Binding
import com.samosaai.masala.core.contract.BindingSource
import com.samosaai.masala.core.contract.HttpBinding
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.contract.WidgetStyle
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.core.layout.LayoutChecker.Environment
import com.samosaai.masala.core.layout.LayoutChecker.Problem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Checks one generated widget (plan `docs/plans/widget-maker.md` §7.3): what the schema can't say —
 * sizes, rules, actions, icons, binding keys, media and web limits, and that no URL carries a secret.
 * [LayoutChecker] calls it for every widget in a layout; the widget library and the Widget Maker call
 * it on a widget alone. Problems carry JSON paths so a repair turn knows what to fix.
 */
object WidgetChecker {

    data class Parsed(val widget: WidgetSpec?, val problems: List<Problem>)

    const val MAX_ID = 64
    const val MAX_TITLE = 60
    const val MAX_COMPONENTS = 12
    const val MAX_CHILDREN = 4
    const val MAX_NESTING = 2
    const val MAX_CHECKLIST_ITEMS = 20
    const val MAX_LIST_ROWS = 8
    const val MAX_CHART_DAYS = 31
    const val MAX_TIMER_SECONDS = 24 * 60 * 60
    const val MAX_VIDEOS = 1
    const val MIN_SNAPSHOT_SECONDS = 2
    const val MIN_HTTP_SECONDS = 30
    const val MIN_BACKGROUND_SECONDS = 15 * 60
    const val MAX_URL = 2048

    /** The values the app can supply for a `second_brain` binding. */
    val SECOND_BRAIN_KEYS = setOf("notes", "latest")

    /** Reads widget JSON the strict way; a document that doesn't decode gives one problem with its path. */
    fun parse(text: String, json: Json = MasalaJson.strict): Parsed = try {
        Parsed(json.decodeFromString(WidgetSpec.serializer(), text), emptyList())
    } catch (e: SerializationException) {
        Parsed(null, listOf(Problem(pathOf(e.message), firstLine(e.message))))
    } catch (e: IllegalArgumentException) {
        Parsed(null, listOf(Problem("$", firstLine(e.message))))
    }

    /** A widget on its own (the library, the Widget Maker), with paths from [path]. */
    fun check(widget: WidgetSpec, env: Environment, path: String = "$"): List<Problem> {
        val problems = mutableListOf<Problem>()
        if (widget.id.isBlank()) problems += Problem("$path.id", "id is empty")
        checkInto(widget, path, env, problems)
        return problems
    }

    /** Everything but the id's uniqueness, which the caller owns (a layout checks it across sections). */
    internal fun checkInto(widget: WidgetSpec, path: String, env: Environment, problems: MutableList<Problem>) {
        if (widget.id.length > MAX_ID) problems += Problem("$path.id", "id has at most $MAX_ID characters")
        if (widget.title != null && widget.title.length > MAX_TITLE) {
            problems += Problem("$path.title", "title has at most $MAX_TITLE characters")
        }
        LayoutChecker.checkSize(widget.size, "$path.size", problems)
        LayoutChecker.checkRules(widget.rules, "$path.rules", widget.size, problems)
        if (widget.components.isEmpty()) problems += Problem("$path.components", "A widget needs a component")

        val count = countComponents(widget.components)
        if (count > MAX_COMPONENTS) problems += Problem("$path.components", "At most $MAX_COMPONENTS components in a widget (has $count)")
        val videos = countComponents(widget.components) { it is WidgetComponent.Video }
        if (videos > MAX_VIDEOS) problems += Problem("$path.components", "At most $MAX_VIDEOS video in a widget")
        val globes = countComponents(widget.components) { it is WidgetComponent.Globe }
        if (globes > 1) problems += Problem("$path.components", "One globe per widget")

        val storageKeys = HashMap<String, String>()
        widget.components.forEachIndexed { c, component ->
            checkComponent(component, "$path.components[$c]", env, depth = 0, storageKeys, problems)
        }
    }

    private fun checkComponent(
        component: WidgetComponent,
        path: String,
        env: Environment,
        depth: Int,
        storageKeys: MutableMap<String, String>,
        problems: MutableList<Problem>,
    ) {
        /** One component owns a storage key; two counters on "glasses" would fight. */
        fun ownKey(key: String, keyPath: String) {
            if (key.isBlank()) problems += Problem(keyPath, "key is empty")
            else storageKeys.putIfAbsent(key, keyPath)?.let { problems += Problem(keyPath, "key '$key' is already used at $it") }
        }

        when (component) {
            is WidgetComponent.Text -> {
                if (component.text == null && component.binding == null) problems += Problem(path, "text needs 'text' or 'binding'")
                component.binding?.let { checkBinding(it, "$path.binding", env, problems) }
                checkStyle(component.style, "$path.style", problems)
            }
            is WidgetComponent.Counter -> {
                ownKey(component.key, "$path.key")
                if (component.step <= 0) problems += Problem("$path.step", "step must be above 0")
                if (component.goal != null && component.goal <= 0) problems += Problem("$path.goal", "goal must be above 0")
                checkStyle(component.style, "$path.style", problems)
            }
            is WidgetComponent.Checklist -> {
                ownKey(component.key, "$path.key")
                if (component.items.size !in 1..MAX_CHECKLIST_ITEMS) {
                    problems += Problem("$path.items", "A checklist has 1 to $MAX_CHECKLIST_ITEMS items")
                }
                component.items.forEachIndexed { i, item -> if (item.isBlank()) problems += Problem("$path.items[$i]", "item is empty") }
            }
            is WidgetComponent.Timer -> {
                ownKey(component.key, "$path.key")
                if (component.seconds !in 1..MAX_TIMER_SECONDS) {
                    problems += Problem("$path.seconds", "seconds must be 1 to $MAX_TIMER_SECONDS")
                }
            }
            is WidgetComponent.Progress -> {
                if (component.max <= 0) problems += Problem("$path.max", "max must be above 0")
                checkBinding(component.binding, "$path.binding", env, problems)
                checkStyle(component.style, "$path.style", problems)
            }
            is WidgetComponent.Chart -> {
                if (component.days !in 1..MAX_CHART_DAYS) problems += Problem("$path.days", "days must be 1 to $MAX_CHART_DAYS")
                checkBinding(component.binding, "$path.binding", env, problems)
            }
            is WidgetComponent.Button -> {
                if (component.label.isBlank()) problems += Problem("$path.label", "label is empty")
                LayoutChecker.checkAction(component.action, "$path.action", env, problems)
                checkButtonAction(component.action, "$path.action", problems)
            }
            is WidgetComponent.Image -> LayoutChecker.checkIcon(component.icon, "$path.icon", env, problems)
            is WidgetComponent.ListRows -> {
                if (component.maxRows !in 1..MAX_LIST_ROWS) problems += Problem("$path.maxRows", "maxRows must be 1 to $MAX_LIST_ROWS")
                checkBinding(component.binding, "$path.binding", env, problems)
                component.rowAction?.let {
                    LayoutChecker.checkAction(it, "$path.rowAction", env, problems)
                    checkButtonAction(it, "$path.rowAction", problems)
                }
                checkStyle(component.style, "$path.style", problems)
            }
            is WidgetComponent.Gauge -> {
                if (component.max <= 0) problems += Problem("$path.max", "max must be above 0")
                checkBinding(component.binding, "$path.binding", env, problems)
                checkStyle(component.style, "$path.style", problems)
            }
            is WidgetComponent.Globe -> {
                component.binding?.let { checkBinding(it, "$path.binding", env, problems) }
                checkStyle(component.style, "$path.style", problems)
            }
            is WidgetComponent.Row, is WidgetComponent.Column -> {
                val children = if (component is WidgetComponent.Row) component.children else (component as WidgetComponent.Column).children
                if (depth + 1 > MAX_NESTING) problems += Problem(path, "Rows and columns nest at most $MAX_NESTING deep")
                if (children.size !in 1..MAX_CHILDREN) problems += Problem("$path.children", "A row or column holds 1 to $MAX_CHILDREN components")
                children.forEachIndexed { i, child ->
                    if (child is WidgetComponent.Video) problems += Problem("$path.children[$i]", "A video can't sit inside a row or column")
                    checkComponent(child, "$path.children[$i]", env, depth + 1, storageKeys, problems)
                }
            }
            is WidgetComponent.Video -> checkSource(component.stream.sourceId, "$path.stream.sourceId", env, problems)
            is WidgetComponent.Snapshot -> {
                checkSource(component.sourceId, "$path.sourceId", env, problems)
                if (component.everySeconds < MIN_SNAPSHOT_SECONDS) {
                    problems += Problem("$path.everySeconds", "everySeconds must be $MIN_SNAPSHOT_SECONDS or more")
                }
            }
            is WidgetComponent.WebValue -> {
                if (component.label.isBlank()) problems += Problem("$path.label", "label is empty")
                checkHttp(component.http, "$path.http", problems)
                checkStyle(component.style, "$path.style", problems)
            }
        }
    }

    private fun checkBinding(binding: Binding, path: String, env: Environment, problems: MutableList<Problem>) {
        val key = binding.key
        when (binding.source) {
            BindingSource.STORAGE -> if (key.isNullOrBlank()) problems += Problem("$path.key", "storage needs a key")
            BindingSource.STREAM_STATUS ->
                if (key.isNullOrBlank()) problems += Problem("$path.key", "stream_status needs the stream source id as key")
                else checkSource(key, "$path.key", env, problems)
            BindingSource.SKILL_RESULT ->
                if (key.isNullOrBlank()) problems += Problem("$path.key", "skill_result needs the skill id as key")
                else if (key !in env.skillIds) problems += Problem("$path.key", "Unknown skill '$key'")
            BindingSource.SECOND_BRAIN ->
                if (key !in SECOND_BRAIN_KEYS) {
                    problems += Problem("$path.key", "second_brain key is one of ${SECOND_BRAIN_KEYS.joinToString()}")
                }
            BindingSource.HTTP -> Unit
            else -> Unit
        }
        if (binding.source == BindingSource.HTTP) {
            if (binding.http == null) problems += Problem("$path.http", "http needs 'http' with a url")
            else checkHttp(binding.http, "$path.http", problems)
        } else if (binding.http != null) {
            problems += Problem("$path.http", "Only an http binding has 'http'")
        }
    }

    private fun checkSource(sourceId: String, path: String, env: Environment, problems: MutableList<Problem>) {
        when {
            sourceId.isBlank() -> problems += Problem(path, "sourceId is empty")
            env.streamSourceIds != null && sourceId !in env.streamSourceIds ->
                problems += Problem(path, "Unknown stream source '$sourceId'")
        }
    }

    internal fun checkHttp(http: HttpBinding, path: String, problems: MutableList<Problem>) {
        urlProblem(http.url)?.let { problems += Problem("$path.url", it) }
        if (!JsonPick.isValid(http.jsonPath)) {
            val reason = runCatching { JsonPick.compile(http.jsonPath) }.exceptionOrNull()?.message
            problems += Problem("$path.jsonPath", "Not a supported path: ${reason ?: http.jsonPath}")
        }
        if (http.every < MIN_HTTP_SECONDS) problems += Problem("$path.every", "every must be $MIN_HTTP_SECONDS seconds or more")
        if (http.background && http.every < MIN_BACKGROUND_SECONDS) {
            problems += Problem("$path.every", "A background refresh runs at most every $MIN_BACKGROUND_SECONDS seconds")
        }
    }

    /**
     * Why [url] can't be fetched by a widget, or null. Only http/https with a host; never credentials
     * (`user:pass@`, `password=`, `token=`…), and never a template that could fill in private values.
     */
    fun urlProblem(url: String): String? {
        val scheme = url.substringBefore("://", "").lowercase()
        val rest = url.substringAfter("://", "")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':').removePrefix("[")
        return when {
            url.length > MAX_URL -> "A url has at most $MAX_URL characters"
            scheme != "http" && scheme != "https" -> "Only http and https urls"
            host.isBlank() -> "The url has no host"
            '@' in authority -> "No credentials in a url; add a stream source or ask for an API key instead"
            SECRET_QUERY.containsMatchIn(url) -> "No passwords or tokens in a url"
            TEMPLATE.containsMatchIn(url) -> "A url can't fill in values ({{…}}, \${…}); write it out"
            url.any { it.isWhitespace() } -> "A url has no spaces"
            else -> null
        }
    }

    private fun checkStyle(style: WidgetStyle?, path: String, problems: MutableList<Problem>) {
        val tint = style?.tint ?: return
        if (tint !in WidgetPalette.tints) problems += Problem("$path.tint", "Unknown tint '$tint'; use one of ${WidgetPalette.tints.sorted()}")
    }

    /** Components including those inside rows and columns, optionally only those matching [where]. */
    fun countComponents(components: List<WidgetComponent>, where: (WidgetComponent) -> Boolean = { true }): Int =
        components.sumOf { c ->
            val nested = when (c) {
                is WidgetComponent.Row -> countComponents(c.children, where)
                is WidgetComponent.Column -> countComponents(c.children, where)
                else -> 0
            }
            (if (where(c)) 1 else 0) + nested
        }

    /** Widget buttons may only run safe tools; taps are explicit, so the gate stays as the tool defines it. */
    val WIDGET_RUN_TOOL_ALLOWLIST = setOf("toggle_torch", "toggle_wifi", "set_timer")

    private fun checkButtonAction(action: MasalaAction, path: String, problems: MutableList<Problem>) {
        if (action is MasalaAction.RunTool && action.name !in WIDGET_RUN_TOOL_ALLOWLIST) {
            problems += Problem("$path.name", "A widget button can only run ${WIDGET_RUN_TOOL_ALLOWLIST.sorted().joinToString(", ")}")
        }
        if (action is MasalaAction.PlaySound && action.kind != "click") {
            problems += Problem("$path.kind", "A widget plays 'click' only")
        }
    }

    private val SECRET_QUERY = Regex("""[?&;](password|passwd|pwd|pass|token|access_token|api_key|apikey|key|secret|auth)=""", RegexOption.IGNORE_CASE)
    private val TEMPLATE = Regex("""\{\{|\$\{|%7B%7B""", RegexOption.IGNORE_CASE)

    private fun pathOf(message: String?): String =
        message?.let { Regex("""at path: (\$\S*)""").find(it)?.groupValues?.get(1) } ?: "$"

    private fun firstLine(message: String?): String = message?.lineSequence()?.firstOrNull().orEmpty().ifBlank { "Not a valid widget" }
}

/** The palette names a widget or icon may use as a tint; the app maps each to a color. */
object WidgetPalette {
    val tints: Set<String> = setOf("orange", "teal", "blue", "green", "red", "purple", "pink", "yellow", "gray")
}
