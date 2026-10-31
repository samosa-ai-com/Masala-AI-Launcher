package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction

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

    /**
     * Moves [itemId] into section [sectionId] just before [beforeId], or to its end when [beforeId] is
     * null or not in that section. What home drops use: the visible order can skip hidden items.
     */
    fun moveBefore(doc: LayoutDoc, itemId: String, sectionId: String, beforeId: String?): LayoutDoc? {
        val section = doc.pages.flatMap { it.sections }.firstOrNull { it.id == sectionId } as? ItemSection ?: return null
        val index = section.items.indexOfFirst { it.id == beforeId }.takeIf { it >= 0 } ?: section.items.size
        return move(doc, itemId, Slot(sectionId, index))
    }

    /** Moves [itemId] to the end of page [pageId]'s first non-dock [ItemSection]. Null if there is none. */
    fun moveToPage(doc: LayoutDoc, itemId: String, pageId: String): LayoutDoc? {
        val page = doc.pages.firstOrNull { it.id == pageId } ?: return null
        val section = page.sections.firstOrNull { it is ItemSection && it !is DockSection } as ItemSection? ?: return null
        val from = find(doc, itemId) ?: return null
        val size = section.items.size - if (from.sectionId == section.id) 1 else 0
        return insert(remove(doc, itemId), from.item, section.id, size)
    }

    /**
     * Drops [draggedId] onto [targetId]: the target is replaced in place by a new folder [folderId] holding
     * the target then the dragged item. Null if either is missing, they are the same, or either is a folder.
     */
    fun makeFolder(doc: LayoutDoc, draggedId: String, targetId: String, folderId: String, label: String): LayoutDoc? {
        if (draggedId == targetId) return null
        val dragged = find(doc, draggedId)?.item ?: return null
        val target = find(doc, targetId)?.item ?: return null
        if (dragged.isFolder || target.isFolder) return null
        val folder = Item(folderId, folderLabel(label), IconRef.Symbol("folder"), MasalaAction.OpenFolder(listOf(target, dragged)))
        return replace(remove(doc, draggedId), targetId) { listOf(folder) }
    }

    /** Appends [itemId] to folder [folderId]. Null if the item is a folder, the target isn't one, or it is full. */
    fun addToFolder(doc: LayoutDoc, itemId: String, folderId: String): LayoutDoc? {
        val item = find(doc, itemId)?.item ?: return null
        val children = find(doc, folderId)?.item?.children ?: return null
        if (item.isFolder || children.size >= LayoutChecker.MAX_FOLDER_ITEMS) return null
        return replace(remove(doc, itemId), folderId) { listOf(it.withChildren(children + item)) }
    }

    /** Takes [itemId] out of [folderId] to just after it. A folder left with one child becomes that child. */
    fun moveOutOfFolder(doc: LayoutDoc, folderId: String, itemId: String): LayoutDoc? {
        val children = find(doc, folderId)?.item?.children ?: return null
        val child = children.firstOrNull { it.id == itemId } ?: return null
        val left = children.filterNot { it.id == itemId }
        return replace(doc, folderId) { folder -> listOf(left.singleOrNull() ?: folder.withChildren(left), child) }
    }

    /** Null if [folderId] isn't a folder or [label] is blank. Long names are cut to [LayoutChecker.MAX_FOLDER_LABEL]. */
    fun renameFolder(doc: LayoutDoc, folderId: String, label: String): LayoutDoc? {
        if (label.isBlank() || find(doc, folderId)?.item?.isFolder != true) return null
        return replace(doc, folderId) { listOf(it.copy(label = folderLabel(label))) }
    }

    private fun folderLabel(label: String) = label.trim().take(LayoutChecker.MAX_FOLDER_LABEL)

    /** Puts the folder's children in its place, in order. Null if [folderId] isn't a folder. */
    fun dissolveFolder(doc: LayoutDoc, folderId: String): LayoutDoc? {
        val children = find(doc, folderId)?.item?.children ?: return null
        return replace(doc, folderId) { children }
    }

    /** The items of [sections], each folder followed by its children: what is on home, wherever it sits. */
    fun itemsWithFolders(sections: List<ItemSection>): List<Item> =
        sections.flatMap { it.items }.flatMap { listOf(it) + it.children.orEmpty() }

    /**
     * [doc] without the items [match] picks, in the sections [where] accepts, folder children included. A
     * folder left with one child becomes that child; one left empty goes.
     */
    fun removeWhere(doc: LayoutDoc, where: (ItemSection) -> Boolean = { true }, match: (Item) -> Boolean): LayoutDoc =
        mapSections(doc) { section ->
            if (!where(section)) return@mapSections section
            section.withItems(
                section.items.filterNot(match).flatMap { item ->
                    val children = item.children ?: return@flatMap listOf(item)
                    val left = children.filterNot(match)
                    when {
                        left.size == children.size -> listOf(item)
                        left.size <= 1 -> left
                        else -> listOf(item.withChildren(left))
                    }
                },
            )
        }

    private val Item.children get() = (action as? MasalaAction.OpenFolder)?.items
    private val Item.isFolder get() = action is MasalaAction.OpenFolder
    private fun Item.withChildren(items: List<Item>) = copy(action = MasalaAction.OpenFolder(items))

    /** Replaces the top-level item [itemId] with what [with] returns for it. */
    private fun replace(doc: LayoutDoc, itemId: String, with: (Item) -> List<Item>) = mapSections(doc) { section ->
        if (section.items.none { it.id == itemId }) section
        else section.withItems(section.items.flatMap { if (it.id == itemId) with(it) else listOf(it) })
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
