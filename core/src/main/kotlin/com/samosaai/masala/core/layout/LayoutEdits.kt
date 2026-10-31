package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc

/** User edits to a base layout by item id (home drag and drop). Each returns null when it can't apply. */
object LayoutEdits {
    /** A slot in an [ItemSection]: insert before [index] (0..size). */
    data class Slot(val sectionId: String, val index: Int)

    /**
     * Moves [itemId] to [to]. Within one section the index counts the item's old place, so moving
     * forward lands before the item that was at [to] index. Null if the item or the target section
     * isn't there, or the target isn't an [ItemSection].
     */
    fun move(doc: LayoutDoc, itemId: String, to: Slot): LayoutDoc? {
        val from = find(doc, itemId) ?: return null
        val target = doc.pages.flatMap { it.sections }.firstOrNull { it.id == to.sectionId }
        if (target !is ItemSection) return null
        var index = to.index
        if (from.sectionId == to.sectionId && from.index < index) index -= 1
        return insert(remove(doc, itemId), from.item, to.sectionId, index)
    }

    /** Moves [itemId] to the end of page [pageId]'s first non-dock [ItemSection]. Null if there is none. */
    fun moveToPage(doc: LayoutDoc, itemId: String, pageId: String): LayoutDoc? {
        val page = doc.pages.firstOrNull { it.id == pageId } ?: return null
        val section = page.sections.firstOrNull { it is ItemSection && it !is DockSection } as ItemSection? ?: return null
        val from = find(doc, itemId) ?: return null
        val size = section.items.size - if (from.sectionId == section.id) 1 else 0
        return insert(remove(doc, itemId), from.item, section.id, size)
    }

    private data class Found(val item: Item, val sectionId: String, val index: Int)

    private fun find(doc: LayoutDoc, itemId: String): Found? {
        for (page in doc.pages) for (section in page.sections) {
            if (section !is ItemSection) continue
            val index = section.items.indexOfFirst { it.id == itemId }
            if (index >= 0) return Found(section.items[index], section.id, index)
        }
        return null
    }

    private fun remove(doc: LayoutDoc, itemId: String) = mapSections(doc) { it.withItems(it.items.filterNot { item -> item.id == itemId }) }

    private fun insert(doc: LayoutDoc, item: Item, sectionId: String, index: Int) = mapSections(doc) { section ->
        if (section.id != sectionId) section
        else section.withItems(section.items.toMutableList().apply { add(index.coerceIn(0, size), item) })
    }

    private fun mapSections(doc: LayoutDoc, change: (ItemSection) -> ItemSection) = doc.copy(
        pages = doc.pages.map { page ->
            page.copy(sections = page.sections.map { if (it is ItemSection) change(it) else it })
        },
    )
}
