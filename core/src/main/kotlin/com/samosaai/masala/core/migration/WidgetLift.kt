package com.samosaai.masala.core.migration

import com.samosaai.masala.core.contract.AddSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.ModePatch
import com.samosaai.masala.core.contract.Section
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.contract.WidgetSpec

/**
 * Moves inline widgets into the widget library (layout schema 3, plan `docs/plans/widget-maker.md`
 * §6.1): every inline [WidgetSpec] becomes a [WidgetRef], and the specs come back for the caller to
 * save. Pure: the app's repository saves [Lifted.widgets] and the new layout in one transaction.
 *
 * Ids are kept, so a widget's storage and tap weights (keyed by widget id) carry over. When the
 * library already has a *different* widget under that id, the lifted one gets the next free id
 * (`water-2`); when it has the same widget, the ref simply points at it.
 */
object WidgetLift {

    data class Lifted<T>(
        val value: T,
        /** New library widgets to save, in the order they were found. */
        val widgets: List<WidgetSpec>,
        /** Old id to new id, for widgets that had to be renamed. */
        val renamed: Map<String, String>,
    )

    fun lift(layout: LayoutDoc, library: Map<String, WidgetSpec> = emptyMap()): Lifted<LayoutDoc> {
        val state = State(library)
        val pages = layout.pages.map { page -> page.copy(sections = page.sections.map(state::section)) }
        return state.result(layout.copy(schemaVersion = LayoutDoc.CURRENT_SCHEMA_VERSION, pages = pages))
    }

    fun lift(mode: ModePatch, library: Map<String, WidgetSpec> = emptyMap()): Lifted<ModePatch> {
        val state = State(library)
        val adds = mode.addSections.map { add: AddSection -> add.copy(section = state.section(add.section)) }
        return state.result(mode.copy(addSections = adds))
    }

    /** True when [layout] still has inline widgets to lift. */
    fun hasInline(layout: LayoutDoc): Boolean = layout.pages.any { page ->
        page.sections.any { (it is WidgetSection && it.widget != null) || (it is StackSection && it.widgets.isNotEmpty()) }
    }

    private class State(library: Map<String, WidgetSpec>) {
        private val known = LinkedHashMap(library)
        private val added = mutableListOf<WidgetSpec>()
        private val renamed = linkedMapOf<String, String>()

        fun section(section: Section): Section = when (section) {
            is WidgetSection -> section.widget?.let { section.copy(widget = null, ref = WidgetRef(store(it))) } ?: section
            is StackSection ->
                if (section.widgets.isEmpty()) section
                else section.copy(widgets = emptyList(), refs = section.widgets.map { WidgetRef(store(it)) })
            else -> section
        }

        /** Saves [spec] (or finds the same one) and returns the id the ref should use. */
        private fun store(spec: WidgetSpec): String {
            val existing = known[spec.id]
            if (existing == spec) return spec.id
            val id = if (existing == null) spec.id else freeId(spec.id).also { renamed[spec.id] = it }
            val stored = spec.copy(id = id)
            known[id] = stored
            added += stored
            return id
        }

        private fun freeId(base: String): String {
            var n = 2
            while ("$base-$n" in known) n++
            return "$base-$n"
        }

        fun <T> result(value: T) = Lifted(value, added.toList(), renamed.toMap())
    }
}
