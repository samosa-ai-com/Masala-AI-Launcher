package com.samosaai.masala.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The home screen: pages → sections → items (arch §3.2). */
@Serializable
data class LayoutDoc(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val pages: List<Page>,
) {
    companion object {
        /** Bump with a migration in [com.samosaai.masala.core.migration.LayoutMigrations]. */
        const val CURRENT_SCHEMA_VERSION = 3
    }
}

@Serializable
data class Page(
    val id: String,
    val title: String? = null,
    val sections: List<Section>,
    val rules: List<Rule> = emptyList(),
)

@Serializable
sealed interface Section {
    val id: String
    val rules: List<Rule>
}

/** A section that holds items, so modes can add items to it. */
sealed interface ItemSection : Section {
    val items: List<Item>
    fun withItems(items: List<Item>): ItemSection
}

@Serializable
@SerialName("header")
data class HeaderSection(
    override val id: String,
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    override val rules: List<Rule> = emptyList(),
) : Section

@Serializable
@SerialName("grid")
data class GridSection(
    override val id: String,
    val columns: Int = 4,
    val title: String? = null,
    override val items: List<Item>,
    override val rules: List<Rule> = emptyList(),
) : ItemSection {
    override fun withItems(items: List<Item>) = copy(items = items)
}

@Serializable
@SerialName("list")
data class ListSection(
    override val id: String,
    val title: String? = null,
    override val items: List<Item>,
    override val rules: List<Rule> = emptyList(),
) : ItemSection {
    override fun withItems(items: List<Item>) = copy(items = items)
}

@Serializable
@SerialName("group")
data class GroupSection(
    override val id: String,
    val label: String,
    val icon: IconRef? = null,
    override val items: List<Item>,
    override val rules: List<Rule> = emptyList(),
) : ItemSection {
    override fun withItems(items: List<Item>) = copy(items = items)
}

/** The pinned bottom section; Masala has no separate dock. */
@Serializable
@SerialName("dock")
data class DockSection(
    override val id: String,
    override val items: List<Item>,
    override val rules: List<Rule> = emptyList(),
) : ItemSection {
    override fun withItems(items: List<Item>) = copy(items = items)
}

/**
 * One generated widget. Schema 3 points at the widget library with [ref]; an inline [widget] is still
 * read for one schema so older exports import (exactly one of the two is set, the checker says so).
 * A resolved layout ([com.samosaai.masala.core.layout.LayoutResolver]) always carries [widget], with
 * [ref] kept so the home screen knows which library widget it is drawing.
 */
@Serializable
@SerialName("widget")
data class WidgetSection(
    override val id: String,
    val widget: WidgetSpec? = null,
    val ref: WidgetRef? = null,
    override val rules: List<Rule> = emptyList(),
) : Section {
    /** The library or inline widget id; empty only for a section the checker rejects. */
    val widgetId: String get() = ref?.widgetId ?: widget?.id.orEmpty()
}

/**
 * Widgets shown one at a time, ordered by rule priority, then tap weight (arch §3.3). Schema 3 uses
 * [refs]; inline [widgets] are still read for one schema. A resolved stack carries [widgets] in
 * display order, with [refs] (when the stack had them) in the same order.
 */
@Serializable
@SerialName("stack")
data class StackSection(
    override val id: String,
    val widgets: List<WidgetSpec> = emptyList(),
    val refs: List<WidgetRef> = emptyList(),
    override val rules: List<Rule> = emptyList(),
) : Section {
    val widgetIds: List<String> get() = if (refs.isNotEmpty()) refs.map { it.widgetId } else widgets.map { it.id }
}

/** A generated HTML mini-app drawn at widget size (plan `docs/plans/widget-maker.md` §3.3). */
@Serializable
@SerialName("html")
data class HtmlSection(
    override val id: String,
    /** The mini app to draw; sandboxed exactly like on its own screen, no phone data. */
    val appId: String,
    /** Height in rows. */
    val rows: Int = 2,
    override val rules: List<Rule> = emptyList(),
) : Section

/** A Pro or hub skill's widget (arch §3.9). Not drawn by the generated-widget renderer. */
@Serializable
@SerialName("skill_widget")
data class SkillWidgetSection(    override val id: String,
    val skillId: String,
    val size: SkillWidgetSize = SkillWidgetSize.MEDIUM,
    override val rules: List<Rule> = emptyList(),
) : Section

@Serializable
enum class SkillWidgetSize {
    @SerialName("small") SMALL,
    @SerialName("medium") MEDIUM,
    @SerialName("large") LARGE,
}

/**
 * A launcher grid inside a page (plan `docs/plans/home-cell-grid.md`): icons and widgets each sit at
 * a cell with a span, so small widgets share a row and a drag lands on a cell. Rows grow as needed.
 */
@Serializable
@SerialName("cells")
data class CellGridSection(
    override val id: String,
    val columns: Int = 4,
    val cells: List<Cell>,
    override val rules: List<Rule> = emptyList(),
) : Section {
    /** Rows in use: one past the lowest cell's bottom edge. */
    val rows: Int get() = cells.maxOfOrNull { it.y + it.h } ?: 0
}

/** One thing on a [CellGridSection]: top-left at ([x], [y]), [w] × [h] cells, 0-based. */
@Serializable
data class Cell(
    val id: String,
    val x: Int,
    val y: Int,
    val w: Int = 1,
    val h: Int = 1,
    val content: CellContent,
    val rules: List<Rule> = emptyList(),
)

@Serializable
sealed interface CellContent {
    /** An icon; always 1 × 1. */
    @Serializable
    @SerialName("item")
    data class ItemCell(val item: Item) : CellContent

    @Serializable
    @SerialName("widget")
    data class WidgetCell(val ref: WidgetRef, val widget: WidgetSpec? = null) : CellContent

    /** Like [StackSection]: [widgets] is filled in when resolved, in display order. */
    @Serializable
    @SerialName("stack")
    data class StackCell(val refs: List<WidgetRef>, val widgets: List<WidgetSpec> = emptyList()) : CellContent

    @Serializable
    @SerialName("html")
    data class HtmlCell(val appId: String) : CellContent

    @Serializable
    @SerialName("skill")
    data class SkillCell(val skillId: String) : CellContent
}

/** A label, an icon and an action. Not always an app: "Gym" can be an action with a symbol icon. */
@Serializable
data class Item(
    val id: String,
    val label: String,
    val icon: IconRef,
    val action: MasalaAction,
    val rules: List<Rule> = emptyList(),
)

@Serializable
sealed interface IconRef {
    /** An installed app's icon. [user] is the profile's user serial; 0 is the main profile. */
    @Serializable
    @SerialName("app")
    data class AppIcon(
        val packageName: String,
        val activity: String? = null,
        val user: Long = 0,
    ) : IconRef

    /** A bundled Material Symbol, by name, with an optional palette tint. */
    @Serializable
    @SerialName("symbol")
    data class Symbol(val name: String, val tint: String? = null) : IconRef

    /** A per-item override stored in `app_overrides` (generated or picked icon). */
    @Serializable
    @SerialName("override")
    data class Override(val id: String) : IconRef
}
