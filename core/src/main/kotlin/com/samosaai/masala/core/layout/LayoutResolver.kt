package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.ModePatch
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.rules.RuleEvaluator

/**
 * What the renderer draws: the base layout with active modes applied and every rule evaluated
 * against [ContextSnapshot]. Hidden pages, sections, items and stack widgets are removed; stacks are
 * ordered by rule priority (tap weight is applied by the app on top).
 */
data class ResolvedLayout(
    val layout: LayoutDoc,
    /** Height in rows of each widget placement, by section ID (one widget twice sits at two heights). */
    val widgetRows: Map<String, Int>,
    val conflicts: List<OverlayMerge.Conflict>,
    /** Library widget IDs a ref pointed at that the library doesn't have. */
    val missingWidgets: List<String> = emptyList(),
)

object LayoutResolver {

    /**
     * [widgets] is the widget library (schema 3 refs point into it). A ref to a widget that isn't in
     * the library is dropped, like a hidden section, and listed in [ResolvedLayout.missingWidgets].
     * The resolved layout carries every drawn widget inline ([WidgetSection.widget],
     * [StackSection.widgets]), so the renderer never needs the library.
     */
    fun resolve(
        base: LayoutDoc,
        activeModes: List<ModePatch>,
        ctx: ContextSnapshot,
        widgets: Map<String, WidgetSpec> = emptyMap(),
    ): ResolvedLayout {
        val merged = OverlayMerge.merge(base, activeModes)
        val rows = mutableMapOf<String, Int>()
        val missing = linkedSetOf<String>()

        fun lookup(ref: WidgetRef): WidgetSpec? = widgets[ref.widgetId] ?: run { missing += ref.widgetId; null }

        /** The widget's range, with this placement's [WidgetRef.rowsOverride] as its default. */
        fun rangeFor(spec: WidgetSpec, ref: WidgetRef?): SizeRange {
            val override = ref?.rowsOverride ?: return spec.size
            return spec.size.copy(default = override.coerceIn(spec.size.min, spec.size.max))
        }

        val pages = merged.layout.pages
            .filter { RuleEvaluator.resolve(it.rules, ctx).visible }
            .map { page ->
                val sections = page.sections
                    .filter { RuleEvaluator.resolve(it.rules, ctx).visible }
                    .mapNotNull { section ->
                        when (section) {
                            is ItemSection -> section.withItems(
                                section.items.filter { RuleEvaluator.resolve(it.rules, ctx).visible },
                            )
                            is WidgetSection -> {
                                val spec = section.widget ?: section.ref?.let(::lookup)
                                if (spec == null) {
                                    null
                                } else {
                                    val r = RuleEvaluator.resolve(spec.rules, ctx, rangeFor(spec, section.ref))
                                    if (!r.visible) null
                                    else section.copy(widget = spec).also { rows[section.id] = r.rows!! }
                                }
                            }
                            is StackSection -> {
                                val entries: List<Pair<WidgetSpec, WidgetRef?>> =
                                    if (section.refs.isNotEmpty()) section.refs.mapNotNull { ref -> lookup(ref)?.let { it to ref } }
                                    else section.widgets.map { it to null }
                                val visible = entries
                                    .map { (spec, ref) -> Triple(spec, ref, RuleEvaluator.resolve(spec.rules, ctx, rangeFor(spec, ref))) }
                                    .filter { (_, _, r) -> r.visible }
                                    .sortedByDescending { (_, _, r) -> r.priority } // stable for ties
                                if (visible.isEmpty()) {
                                    null
                                } else {
                                    rows[section.id] = visible.maxOf { (_, _, r) -> r.rows!! }
                                    section.copy(
                                        widgets = visible.map { it.first },
                                        refs = if (section.refs.isEmpty()) emptyList() else visible.map { it.second!! },
                                    )
                                }
                            }
                            else -> section
                        }
                    }
                page.copy(sections = sections)
            }

        return ResolvedLayout(merged.layout.copy(pages = pages), rows, merged.conflicts, missing.toList())
    }
}
