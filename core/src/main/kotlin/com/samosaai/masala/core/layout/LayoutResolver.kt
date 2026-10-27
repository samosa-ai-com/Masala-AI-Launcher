package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.ModePatch
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.rules.RuleEvaluator

/**
 * What the renderer draws: the base layout with active modes applied and every rule evaluated
 * against [ContextSnapshot]. Hidden pages, sections, items and stack widgets are removed; stacks are
 * ordered by rule priority (tap weight is applied by the app on top).
 */
data class ResolvedLayout(
    val layout: LayoutDoc,
    /** Height in rows of each widget, by widget ID. */
    val widgetRows: Map<String, Int>,
    val conflicts: List<OverlayMerge.Conflict>,
)

object LayoutResolver {

    fun resolve(base: LayoutDoc, activeModes: List<ModePatch>, ctx: ContextSnapshot): ResolvedLayout {
        val merged = OverlayMerge.merge(base, activeModes)
        val rows = mutableMapOf<String, Int>()

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
                                val r = RuleEvaluator.resolve(section.widget.rules, ctx, section.widget.size)
                                if (!r.visible) null
                                else section.also { rows[it.widget.id] = r.rows!! }
                            }
                            is StackSection -> {
                                val visible = section.widgets
                                    .map { it to RuleEvaluator.resolve(it.rules, ctx, it.size) }
                                    .filter { (_, r) -> r.visible }
                                    .sortedByDescending { (_, r) -> r.priority } // stable for ties
                                visible.forEach { (w, r) -> rows[w.id] = r.rows!! }
                                if (visible.isEmpty()) null else section.copy(widgets = visible.map { it.first })
                            }
                            else -> section
                        }
                    }
                page.copy(sections = sections)
            }

        return ResolvedLayout(merged.layout.copy(pages = pages), rows, merged.conflicts)
    }
}
