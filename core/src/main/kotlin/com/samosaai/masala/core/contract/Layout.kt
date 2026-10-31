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
        const val CURRENT_SCHEMA_VERSION = 2
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

@Serializable
@SerialName("widget")
data class WidgetSection(
    override val id: String,
    val widget: WidgetSpec,
    override val rules: List<Rule> = emptyList(),
) : Section

/** Widgets shown one at a time, ordered by rule priority, then tap weight (arch §3.3). */
@Serializable
@SerialName("stack")
data class StackSection(
    override val id: String,
    val widgets: List<WidgetSpec>,
    override val rules: List<Rule> = emptyList(),
) : Section

/** A Pro or hub skill's widget (arch §3.9). Not drawn by the generated-widget renderer. */
@Serializable
@SerialName("skill_widget")
data class SkillWidgetSection(
    override val id: String,
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
