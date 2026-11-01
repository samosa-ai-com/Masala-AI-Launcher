package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.Condition
import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.Effect
import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.GroupSection
import com.samosaai.masala.core.contract.HeaderSection
import com.samosaai.masala.core.contract.HtmlSection
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.ListSection
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.Rule
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.SkillWidgetSection
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.migration.LayoutMigrations
import com.samosaai.masala.core.widgets.WidgetChecker
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Checks a layout before it is saved, imported or shown (arch §3.2): the schema (does it decode) and
 * what the schema can't say — an app that isn't installed for that user, duplicate IDs, too many items,
 * a size outside the widget's range, an unknown icon or skill, arguments an action can't use. Every
 * problem comes back with the JSON path it is at (`$.pages[0].sections[1].items[2].action`), so a model
 * can be told exactly what to fix.
 */
object LayoutChecker {

    /** What the phone knows, so the checker can tell a real package from a made-up one. */
    data class Environment(
        /** Installed apps as `package` to the user serials it is installed for. */
        val installedApps: Map<String, Set<Long>>,
        /** Skill IDs from the registry. */
        val skillIds: Set<String>,
        val symbols: Set<String> = KnownSymbols.names,
        /** Tool names `run_tool` may use; null skips the check. */
        val toolNames: Set<String>? = null,
        /** Mode IDs `set_mode` may use; null skips the check. */
        val modeIds: Set<String>? = null,
        /** The widget library, for refs (schema 3); null skips the check. */
        val widgets: Map<String, WidgetSpec>? = null,
        /** Stream source IDs `video`/`snapshot` may use; null skips the check. */
        val streamSourceIds: Set<String>? = null,
        /** Mini-app IDs `html` sections may show; null skips the check. */
        val miniAppIds: Set<String>? = null,
    ) {
        fun installed(packageName: String, user: Long) = user in installedApps[packageName].orEmpty()
    }

    data class Problem(val path: String, val message: String) {
        override fun toString() = "$path: $message"
    }

    data class Parsed(val layout: LayoutDoc?, val problems: List<Problem>)

    const val MAX_PAGES = 8
    const val MAX_SECTIONS_PER_PAGE = 24
    const val MAX_ITEMS_PER_SECTION = 48
    const val MAX_DOCK_ITEMS = 6
    const val MAX_COLUMNS = 6
    const val MAX_WIDGETS_IN_STACK = 8
    const val MAX_HTML_PER_PAGE = 2
    /** Widget rows on one page (widget sections, tallest of each stack, HTML): home stays one screenful. */
    const val MAX_WIDGET_ROWS_PER_PAGE = 8
    const val MAX_ROWS = 8
    const val MAX_FOLDER_ITEMS = 16
    const val MAX_FOLDER_LABEL = 60

    /**
     * Reads layout JSON the strict way, migrating older schema versions first. A document that doesn't
     * decode gives one problem with the path the decoder stopped at.
     */
    fun parse(text: String, json: Json = MasalaJson.strict): Parsed = try {
        val element = json.parseToJsonElement(text)
        if (element !is JsonObject) {
            Parsed(null, listOf(Problem("$", "A layout is a JSON object")))
        } else {
            // Migrate, then decode from text again: a decoder working on a tree can't say where it stopped.
            val migrated = LayoutMigrations.migrate(element)
            Parsed(json.decodeFromString(LayoutDoc.serializer(), migrated.toString()), emptyList())
        }
    } catch (e: SerializationException) {
        Parsed(null, listOf(Problem(pathOf(e.message), firstLine(e.message))))
    } catch (e: IllegalArgumentException) {
        Parsed(null, listOf(Problem("$", firstLine(e.message))))
    }

    fun check(layout: LayoutDoc, env: Environment): List<Problem> {
        val problems = mutableListOf<Problem>()
        val ids = HashMap<String, String>() // id -> path of the first use

        fun id(value: String, path: String) {
            if (value.isBlank()) problems += Problem("$path.id", "id is empty")
            else ids.putIfAbsent(value, path)?.let { problems += Problem("$path.id", "id '$value' is already used at $it") }
        }

        if (layout.pages.isEmpty()) problems += Problem("$.pages", "A layout needs at least one page")
        if (layout.pages.size > MAX_PAGES) problems += Problem("$.pages", "At most $MAX_PAGES pages")

        layout.pages.forEachIndexed { p, page ->
            val pagePath = "$.pages[$p]"
            id(page.id, pagePath)
            checkRules(page.rules, "$pagePath.rules", null, problems)
            if (page.sections.size > MAX_SECTIONS_PER_PAGE) {
                problems += Problem("$pagePath.sections", "At most $MAX_SECTIONS_PER_PAGE sections on a page")
            }
            // An HTML widget is a live WebView: at most two per page, so home keeps its frame time.
            if (page.sections.filterIsInstance<HtmlSection>().size > MAX_HTML_PER_PAGE) {
                problems += Problem("$pagePath.sections", "At most $MAX_HTML_PER_PAGE HTML widgets on a page")
            }
            // Widgets share one screenful per page; the rest belong on another page.
            val widgetSizes = env.widgets?.mapValues { it.value.size.default }.orEmpty()
            if (LayoutEdits.widgetRowsUsedSections(page.sections, widgetSizes) > MAX_WIDGET_ROWS_PER_PAGE) {
                problems += Problem("$pagePath.sections", "At most $MAX_WIDGET_ROWS_PER_PAGE widget rows on a page")
            }
            page.sections.forEachIndexed { s, section ->
                val path = "$pagePath.sections[$s]"
                id(section.id, path)
                checkRules(section.rules, "$path.rules", null, problems)
                when (section) {
                    is HeaderSection -> Unit
                    is ItemSection -> checkItemSection(section, path, env, ::id, problems)
                    is WidgetSection -> when {
                        section.widget != null && section.ref != null ->
                            problems += Problem(path, "A widget section has 'ref' or 'widget', not both")
                        section.ref != null -> checkRef(section.ref, "$path.ref", env, problems)
                        section.widget != null -> checkWidget(section.widget, "$path.widget", env, ::id, problems)
                        else -> problems += Problem("$path.ref", "A widget section needs 'ref'")
                    }
                    is StackSection -> checkStack(section, path, env, ::id, problems)
                    is SkillWidgetSection ->
                        if (section.skillId !in env.skillIds) problems += Problem("$path.skillId", "Unknown skill '${section.skillId}'")
                    is HtmlSection -> checkHtml(section, path, env, problems)
                }
            }
        }
        return problems
    }

    private fun checkItemSection(
        section: ItemSection,
        path: String,
        env: Environment,
        id: (String, String) -> Unit,
        problems: MutableList<Problem>,
    ) {
        val limit = if (section is DockSection) MAX_DOCK_ITEMS else MAX_ITEMS_PER_SECTION
        if (section.items.size > limit) problems += Problem("$path.items", "At most $limit items in this section")
        when (section) {
            is GridSection -> if (section.columns !in 1..MAX_COLUMNS) {
                problems += Problem("$path.columns", "columns must be 1 to $MAX_COLUMNS")
            }
            is ListSection, is GroupSection, is DockSection -> Unit
        }
        section.items.forEachIndexed { i, item ->
            val itemPath = "$path.items[$i]"
            id(item.id, itemPath)
            checkItem(item, itemPath, env, id, problems)
        }
    }

    private fun checkItem(item: Item, path: String, env: Environment, id: (String, String) -> Unit, problems: MutableList<Problem>) {
        if (item.label.isBlank()) problems += Problem("$path.label", "label is empty")
        if (item.action is MasalaAction.OpenFolder && item.label.length > MAX_FOLDER_LABEL) {
            problems += Problem("$path.label", "A folder name has at most $MAX_FOLDER_LABEL characters")
        }
        checkIcon(item.icon, "$path.icon", env, problems)
        checkAction(item.action, "$path.action", env, problems, id)
        checkRules(item.rules, "$path.rules", null, problems)
    }

    /** One level only: a folder's children are plain items with no rules. */
    private fun checkFolder(
        folder: MasalaAction.OpenFolder,
        path: String,
        env: Environment,
        id: ((String, String) -> Unit)?,
        problems: MutableList<Problem>,
    ) {
        if (id == null) {
            problems += Problem(path, "Folders can only be home items")
            return
        }
        if (folder.items.size !in 1..MAX_FOLDER_ITEMS) {
            problems += Problem("$path.items", "A folder holds 1 to $MAX_FOLDER_ITEMS items")
        }
        folder.items.forEachIndexed { i, child ->
            val childPath = "$path.items[$i]"
            id(child.id, childPath)
            if (child.action is MasalaAction.OpenFolder) {
                problems += Problem("$childPath.action", "Folders can't contain folders")
            } else {
                checkItem(child, childPath, env, id, problems)
            }
            if (child.rules.isNotEmpty()) problems += Problem("$childPath.rules", "Items in a folder can't have rules")
        }
    }

    internal fun checkIcon(icon: IconRef, path: String, env: Environment, problems: MutableList<Problem>) {
        when (icon) {
            is IconRef.AppIcon ->
                if (!env.installed(icon.packageName, icon.user)) {
                    problems += Problem("$path.packageName", "${icon.packageName} is not installed for user ${icon.user}")
                }
            is IconRef.Symbol ->
                if (icon.name !in env.symbols) problems += Problem("$path.name", "Unknown icon '${icon.name}'")
            is IconRef.Override -> Unit
        }
    }

    /** [id] registers folder children's ids; without it (a widget button) a folder is a problem. */
    fun checkAction(
        action: MasalaAction,
        path: String,
        env: Environment,
        problems: MutableList<Problem>,
        id: ((String, String) -> Unit)? = null,
    ) {
        when (action) {
            is MasalaAction.OpenFolder -> checkFolder(action, path, env, id, problems)
            is MasalaAction.LaunchApp -> if (!env.installed(action.packageName, action.user)) {
                problems += Problem("$path.packageName", "${action.packageName} is not installed for user ${action.user}")
            }
            is MasalaAction.LaunchShortcut -> {
                if (!env.installed(action.packageName, action.user)) {
                    problems += Problem("$path.packageName", "${action.packageName} is not installed for user ${action.user}")
                }
                if (action.shortcutId.isBlank()) problems += Problem("$path.shortcutId", "shortcutId is empty")
            }
            is MasalaAction.OpenUrl -> {
                val scheme = action.url.substringBefore("://", "").lowercase()
                if (scheme != "http" && scheme != "https") problems += Problem("$path.url", "Only http and https links can be opened")
            }
            is MasalaAction.Dial -> if (!action.number.matches(PHONE_NUMBER)) {
                problems += Problem("$path.number", "Not a phone number: '${action.number}'")
            }
            is MasalaAction.ComposeMessage -> {
                if (action.to == null && action.body == null) problems += Problem(path, "compose_message needs 'to' or 'body'")
                if (action.to != null && !action.to.matches(PHONE_NUMBER)) problems += Problem("$path.to", "Not a phone number: '${action.to}'")
            }
            is MasalaAction.SetAlarm -> {
                if (action.hour !in 0..23) problems += Problem("$path.hour", "hour must be 0 to 23")
                if (action.minute !in 0..59) problems += Problem("$path.minute", "minute must be 0 to 59")
            }
            is MasalaAction.StartTimer -> if (action.seconds !in 1..MAX_TIMER_SECONDS) {
                problems += Problem("$path.seconds", "seconds must be 1 to $MAX_TIMER_SECONDS")
            }
            is MasalaAction.SetMode -> if (env.modeIds != null && action.id !in env.modeIds) {
                problems += Problem("$path.id", "Unknown mode '${action.id}'")
            }
            is MasalaAction.RunSkill -> if (action.skillId !in env.skillIds) {
                problems += Problem("$path.skillId", "Unknown skill '${action.skillId}'")
            }
            is MasalaAction.RunTool -> if (env.toolNames != null && action.name !in env.toolNames) {
                problems += Problem("$path.name", "Unknown tool '${action.name}'")
            }
            // An empty ask_masala prompt opens the ask box with nothing typed.
            MasalaAction.OpenDrawer, MasalaAction.ExpandNotifications, is MasalaAction.Search,
            is MasalaAction.OpenMiniApp, is MasalaAction.AskMasala -> Unit
        }
    }

    /** An inline widget (schema 2 and older): its id is unique in the layout like any other. */
    private fun checkWidget(
        widget: WidgetSpec,
        path: String,
        env: Environment,
        id: (String, String) -> Unit,
        problems: MutableList<Problem>,
    ) {
        id(widget.id, path)
        WidgetChecker.checkInto(widget, path, env, problems)
    }

    /** A library widget. The same widget may sit in several places, so its id isn't registered. */
    private fun checkRef(ref: WidgetRef, path: String, env: Environment, problems: MutableList<Problem>) {
        if (ref.widgetId.isBlank()) {
            problems += Problem("$path.widgetId", "widgetId is empty")
            return
        }
        val spec = env.widgets?.get(ref.widgetId)
        if (env.widgets != null && spec == null) problems += Problem("$path.widgetId", "Unknown widget '${ref.widgetId}'")
        val rows = ref.rowsOverride ?: return
        val range = spec?.size ?: SizeRange(1, 1, MAX_ROWS)
        if (rows !in range.min..range.max) {
            problems += Problem("$path.rowsOverride", "$rows is outside the widget's size range ${range.min}..${range.max}")
        }
    }

    /** A generated HTML widget: a known mini app at widget size, same sandbox as its screen. */
    private fun checkHtml(section: HtmlSection, path: String, env: Environment, problems: MutableList<Problem>) {
        if (section.appId.isBlank()) {
            problems += Problem("$path.appId", "An HTML widget needs an app")
            return
        }
        if (env.miniAppIds != null && section.appId !in env.miniAppIds) {
            problems += Problem("$path.appId", "Unknown mini app '${section.appId}'")
        }
        if (section.rows !in 1..MAX_ROWS) problems += Problem("$path.rows", "rows is 1..$MAX_ROWS")
    }

    private fun checkStack(
        section: StackSection,
        path: String,
        env: Environment,
        id: (String, String) -> Unit,
        problems: MutableList<Problem>,
    ) {
        if (section.widgets.isNotEmpty() && section.refs.isNotEmpty()) {
            problems += Problem(path, "A stack has 'refs' or 'widgets', not both")
            return
        }
        val count = section.widgets.size + section.refs.size
        val field = if (section.widgets.isNotEmpty()) "widgets" else "refs"
        if (count == 0) problems += Problem("$path.refs", "A stack needs a widget")
        if (count > MAX_WIDGETS_IN_STACK) problems += Problem("$path.$field", "At most $MAX_WIDGETS_IN_STACK widgets in a stack")
        section.widgets.forEachIndexed { w, widget -> checkWidget(widget, "$path.widgets[$w]", env, id, problems) }
        val seen = HashMap<String, Int>()
        section.refs.forEachIndexed { r, ref ->
            checkRef(ref, "$path.refs[$r]", env, problems)
            seen.putIfAbsent(ref.widgetId, r)?.let {
                problems += Problem("$path.refs[$r].widgetId", "'${ref.widgetId}' is already in this stack at refs[$it]")
            }
        }
    }

    internal fun checkSize(size: SizeRange, path: String, problems: MutableList<Problem>) {
        if (size.min < 1 || size.max > MAX_ROWS || size.min > size.default || size.default > size.max) {
            problems += Problem(path, "Sizes need 1 <= min <= default <= max <= $MAX_ROWS")
        }
    }

    internal fun checkRules(rules: List<Rule>, path: String, range: SizeRange?, problems: MutableList<Problem>) {
        rules.forEachIndexed { r, rule ->
            val rulePath = "$path[$r]"
            checkCondition(rule.condition, "$rulePath.condition", problems)
            val effect = rule.effect
            if (effect is Effect.Size) {
                if (range == null) {
                    problems += Problem("$rulePath.effect", "Only a widget's rules can set a size")
                } else if (effect.rows !in range.min..range.max) {
                    problems += Problem("$rulePath.effect.rows", "${effect.rows} is outside the widget's size range ${range.min}..${range.max}")
                }
            }
        }
    }

    private fun checkCondition(condition: Condition, path: String, problems: MutableList<Problem>) {
        when (condition) {
            is Condition.MinutesToNextEvent ->
                if (condition.minMinutes > condition.maxMinutes) problems += Problem(path, "minMinutes is above maxMinutes")
            is Condition.Sleep ->
                if (condition.minHours != null && condition.maxHours != null && condition.minHours > condition.maxHours) {
                    problems += Problem(path, "minHours is above maxHours")
                }
            is Condition.Steps ->
                if (condition.min != null && condition.max != null && condition.min > condition.max) {
                    problems += Problem(path, "min is above max")
                }
            is Condition.Weather ->
                if (condition.minTempC != null && condition.maxTempC != null && condition.minTempC > condition.maxTempC) {
                    problems += Problem(path, "minTempC is above maxTempC")
                }
            is Condition.UrgentCount -> if (condition.atLeast < 1) problems += Problem("$path.atLeast", "atLeast must be 1 or more")
            is Condition.AllOf -> condition.conditions.forEachIndexed { i, c -> checkCondition(c, "$path.conditions[$i]", problems) }
            is Condition.AnyOf -> condition.conditions.forEachIndexed { i, c -> checkCondition(c, "$path.conditions[$i]", problems) }
            is Condition.Not -> checkCondition(condition.condition, "$path.condition", problems)
            else -> Unit
        }
    }

    private val PHONE_NUMBER = Regex("""^\+?[0-9 ()\-.*#]{3,32}$""")
    private const val MAX_TIMER_SECONDS = WidgetChecker.MAX_TIMER_SECONDS

    /** kotlinx puts `at path: $.pages[0]...` at the end of the first line of a decoding error. */
    private fun pathOf(message: String?): String =
        message?.let { Regex("""at path: (\$\S*)""").find(it)?.groupValues?.get(1) } ?: "$"

    private fun firstLine(message: String?): String = message?.lineSequence()?.firstOrNull().orEmpty().ifBlank { "Not a valid layout" }
}

/** Material Symbol names a layout may use; the app maps each to a drawable (a test keeps the two in step). */
object KnownSymbols {
    /**
     * The set a layout may name, and the every-version list: the schema offers only [promptable], so a
     * model can't wander into the long tail, while a layout imported from a newer build still draws.
     */
    val promptable: Set<String> = setOf(
        "alarm", "apps", "battery_full", "bedtime", "book", "calendar_today", "call", "camera", "chat", "checklist",
        "cloud", "directions_car", "email", "event", "fitness_center", "flag", "folder", "headphones", "home", "info",
        "language", "location_on", "mic", "music_note", "notifications", "photo", "play_arrow", "priority_high",
        "queue_music", "restaurant", "school", "search", "settings", "shopping_cart", "sports_esports", "star", "timer", "tune",
        "wb_sunny", "widgets", "work",
    )

    /** Everything the app can draw, offered by the icon picker and accepted by validation. */
    val all: Set<String> = promptable + setOf(
        "calculate", "contacts", "flight", "map", "movie", "newspaper", "palette", "pets", "public", "radio",
        "sports_soccer", "task_alt", "translate", "videogame_asset", "wallet", "wifi",
    )

    /** The historical name for the whole catalog. */
    val names: Set<String> get() = all
}
