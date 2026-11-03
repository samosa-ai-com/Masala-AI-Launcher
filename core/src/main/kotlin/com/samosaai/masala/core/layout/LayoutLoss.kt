package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.WidgetSection

/**
 * What a replacement layout would take off home. A model that never saw the current document can
 * submit a small valid one and wipe the user's home; `edit_layout` refuses that unless the user asked
 * for a fresh start. Things are matched by what they open (or the widget they show), not by item id,
 * so a model that rearranges and renames ids is not mistaken for one that deletes.
 */
object LayoutLoss {
    /** Everything [doc] shows on any page: apps, shortcuts and mini apps by action, widgets by id. */
    fun contents(doc: LayoutDoc): Set<String> = doc.pages.flatMap { page ->
        page.sections.flatMap { s ->
            when (s) {
                is ItemSection -> LayoutEdits.itemsWithFolders(listOf(s)).filter { it.action !is MasalaAction.OpenFolder }.map { "item:${it.action}" }
                is WidgetSection -> listOf("widget:${s.widgetId}")
                else -> emptyList()
            }
        }
    }.toSet()

    /** How many of [before]'s things [after] keeps, and how many [before] had. */
    fun kept(before: LayoutDoc, after: LayoutDoc): Pair<Int, Int> {
        val had = contents(before)
        return (had intersect contents(after)).size to had.size
    }

    /** True when [after] keeps less than half of [before], on a home with at least [MIN_THINGS] things. */
    fun dropsMost(before: LayoutDoc, after: LayoutDoc): Boolean {
        val (kept, had) = kept(before, after)
        return had >= MIN_THINGS && kept * 2 < had
    }

    const val MIN_THINGS = 3
}
