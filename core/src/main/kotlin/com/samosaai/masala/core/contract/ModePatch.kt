package com.samosaai.masala.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A mode is a small patch on the base layout, not a copy of it (arch §3.2). Adds are applied before
 * hides, and patches are applied in [order], then by [id].
 */
@Serializable
data class ModePatch(
    val id: String,
    val name: String,
    val order: Int = 0,
    val trigger: ModeTrigger = ModeTrigger.Manual,
    val hideItems: List<String> = emptyList(),
    val hideSections: List<String> = emptyList(),
    val addItems: List<AddItem> = emptyList(),
    val addSections: List<AddSection> = emptyList(),
    val dnd: DndPolicy? = null,
)

/** Adds [item] to the item section [sectionId], after the item [after] (null: at the end). */
@Serializable
data class AddItem(val sectionId: String, val item: Item, val after: String? = null)

/** Adds [section] to [pageId], after the section [after] (null: at the end). */
@Serializable
data class AddSection(val pageId: String, val section: Section, val after: String? = null)

@Serializable
sealed interface ModeTrigger {
    @Serializable
    @SerialName("manual")
    data object Manual : ModeTrigger

    /** On while [condition] holds, for example a time window or a Bluetooth device. */
    @Serializable
    @SerialName("rule")
    data class WhenTrue(val condition: Condition) : ModeTrigger
}

/** Masala's own DND rule while the mode is on. */
@Serializable
data class DndPolicy(
    val filter: DndFilter,
    /** Let urgent notifications through (starred contacts, wiki people, keywords). */
    val allowUrgent: Boolean = true,
)

@Serializable
enum class DndFilter {
    @SerialName("priority") PRIORITY,
    @SerialName("alarms") ALARMS,
    @SerialName("none") NONE,
}
