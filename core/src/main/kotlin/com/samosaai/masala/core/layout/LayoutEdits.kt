package com.samosaai.masala.core.layout

import com.samosaai.masala.core.migration.CellLift
import com.samosaai.masala.core.contract.CellContent
import com.samosaai.masala.core.contract.Cell
import com.samosaai.masala.core.contract.CellGridSection
import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.Emphasis
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
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.Section
import com.samosaai.masala.core.contract.SkillWidgetSection
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.contract.WidgetSpec

/** User edits to a base layout by item id (home drag and drop). Each returns null when it can't apply. */
object LayoutEdits {
    /**
     * Moves section [sectionId] within its page by [steps] (negative is up), or to [targetPageId]'s
     * end (or before [beforeSectionId]) across pages. Widget sections move like any other section;
     * the dock never moves and nothing ever lands after it. Null when the section is missing, the
     * target page is missing, or the move changes nothing. Pure reorder — the caller checks the
     * page height cap.
     */
    fun moveSection(
        doc: LayoutDoc,
        sectionId: String,
        steps: Int = 0,
        targetPageId: String? = null,
        beforeSectionId: String? = null,
    ): LayoutDoc? {
        val fromPage = doc.pages.firstOrNull { p -> p.sections.any { it.id == sectionId } } ?: return null
        val section = fromPage.sections.first { it.id == sectionId }
        if (section is DockSection) return null
        val toPageId = targetPageId ?: fromPage.id
        if (doc.pages.none { it.id == toPageId }) return null
        // Insert before the dock when no sibling names the spot, so a drop at the page end can
        // never strand a widget below it (and the indicator, drawn at the last widget, stays true).
        fun endOf(sections: List<Section>): Int =
            sections.indexOfFirst { it is DockSection }.takeIf { it >= 0 } ?: sections.size
        val next = if (targetPageId == null && beforeSectionId == null) {
            val idx = fromPage.sections.indexOfFirst { it.id == sectionId }
            val to = (idx + steps).coerceIn(0, fromPage.sections.size - 1)
            if (to == idx) return null
            val rest = fromPage.sections.filterNot { it.id == sectionId }.toMutableList()
            // After removal the list shrank by one below the old index; clamp into it, above the dock.
            rest.add(to.coerceIn(0, rest.size).coerceAtMost(endOf(rest)), section)
            doc.copy(pages = doc.pages.map { if (it.id != fromPage.id) it else it.copy(sections = rest) })
        } else {
            val without = doc.pages.map { p ->
                if (p.id != fromPage.id) p else p.copy(sections = p.sections.filterNot { it.id == sectionId })
            }
            val dest = without.first { it.id == toPageId }
            val at = beforeSectionId?.let { id -> dest.sections.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                ?: endOf(dest.sections)
            val sections = dest.sections.toMutableList().also { it.add(at.coerceIn(0, it.size), section) }
            doc.copy(pages = without.map { if (it.id != toPageId) it else it.copy(sections = sections) })
        }
        // Same order in every page: the drop changed nothing.
        val same = next.pages.zip(doc.pages).all { (a, b) -> a.sections.map { it.id } == b.sections.map { it.id } }
        return if (same) null else next
    }

    /** A text component wraps at about this many characters a line; the renderer caps it at four. */
    private const val MAX_TEXT_LINES = 4

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
     * Moves [itemId] into [sectionId] just before the first item the document still has at or after
     * [visibleIndex] in [visible], ending when nothing does. [visible] is the on-screen order of the
     * section without the dragged item: it skips hidden items and holds items a mode added, which the
     * saved document cannot address. Null when either the item or the section isn't in [doc].
     */
    fun moveBeforeVisible(doc: LayoutDoc, itemId: String, sectionId: String, visible: List<String>, visibleIndex: Int): LayoutDoc? {
        val section = doc.pages.flatMap { it.sections }.firstOrNull { it.id == sectionId } as? ItemSection ?: return null
        val saved = section.items.mapTo(HashSet()) { it.id }
        val before = visible.drop(visibleIndex.coerceIn(0, visible.size)).firstOrNull { it in saved }
        return moveBefore(doc, itemId, sectionId, before)
    }

    /**
     * Renames every item [match] selects, top-level or inside a folder. Null when nothing matched, or
     * the name is blank.
     */
    fun renameWhere(doc: LayoutDoc, label: String, match: (Item) -> Boolean): LayoutDoc? {
        val clean = label.trim().take(LayoutChecker.MAX_FOLDER_LABEL)
        if (clean.isEmpty()) return null
        var found = false
        val next = mapSections(doc) { section ->
            section.withItems(section.items.map { item ->
                val renamed = if (match(item)) { found = true; item.copy(label = clean) } else item
                val children = renamed.children
                if (children != null && children.any(match)) {
                    found = true
                    renamed.withChildren(children.map { if (match(it)) it.copy(label = clean) else it })
                } else {
                    renamed
                }
            })
        }
        return next.takeIf { found }
    }

    /** Renames the top-level item or the folder child [itemId]. */
    fun renameItem(doc: LayoutDoc, itemId: String, label: String): LayoutDoc? =
        renameWhere(doc, label) { it.id == itemId }

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

    /**
     * Takes [itemId] out of [folderId] to just after it. A folder left with one child becomes that child;
     * one left with none (an imported one-item folder) is gone.
     */
    fun moveOutOfFolder(doc: LayoutDoc, folderId: String, itemId: String): LayoutDoc? {
        val children = find(doc, folderId)?.item?.children ?: return null
        val child = children.firstOrNull { it.id == itemId } ?: return null
        val left = children.filterNot { it.id == itemId }
        return replace(doc, folderId) { folder ->
            when (left.size) {
                0 -> listOf(child)
                1 -> listOf(left.single(), child)
                else -> listOf(folder.withChildren(left), child)
            }
        }
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

    // ---- generated widgets (plan docs/plans/widget-maker.md §6.1) ----

    /** What placing found: the new document, and where the section landed. */
    data class Placed(val doc: LayoutDoc, val pageId: String, val sectionId: String)

    /** Widget rows already on [pageId]: header, item grids, widgets at rendered size, HTML, skills. */
    fun widgetRowsUsed(doc: LayoutDoc, pageId: String, sizes: Map<String, WidgetSpec> = emptyMap()): Int {
        val page = doc.pages.firstOrNull { it.id == pageId } ?: return 0
        return page.sections.sumOf { estimateSectionRows(it, sizes) }
    }

    /**
     * A section's rendered height in rows (48 dp units). Widgets count what they draw — title,
     * components, padding, at least their default rows — because cards grow past their minimum;
     * grids count title plus two rows per item row; the header counts clock and date; the dock is
     * pinned and costs nothing.
     */
    fun estimateSectionRows(section: Section, specs: Map<String, WidgetSpec> = emptyMap()): Int =
        when (section) {
            is WidgetSection -> section.ref?.let { ref ->
                val spec = specs[ref.widgetId]
                if (spec == null) ref.rowsOverride ?: 2
                else maxOf(estimateWidgetRows(spec), ref.rowsOverride ?: spec.size.default)
            } ?: section.widget?.let { maxOf(estimateWidgetRows(it), it.size.default) } ?: 2
            is StackSection -> {
                val inline = section.widgets.maxOfOrNull { maxOf(estimateWidgetRows(it), it.size.default) } ?: 0
                val refs = section.refs.maxOfOrNull { ref ->
                    val spec = specs[ref.widgetId]
                    if (spec == null) ref.rowsOverride ?: 2
                    else maxOf(estimateWidgetRows(spec), ref.rowsOverride ?: spec.size.default)
                } ?: 0
                maxOf(inline, refs)
            }
            is HtmlSection -> section.rows
            is SkillWidgetSection -> 4
            is HeaderSection -> (if (section.showClock) 2 else 0) + (if (section.showDate) 1 else 0)
            is GridSection -> (if (!section.title.isNullOrBlank()) 1 else 0) + ((section.items.size + section.columns - 1) / section.columns) * 2
            is ListSection -> (if (!section.title.isNullOrBlank()) 1 else 0) + section.items.size
            is GroupSection -> 1
            is DockSection -> 0
            // A grid row is an icon cell's height, two 48 dp units like an item grid row.
            is CellGridSection -> section.rows * 2
        }

    /** Declared widget rows for the checker: overrides and defaults, the author's height intent. */
    fun declaredWidgetRows(sections: List<Section>, sizes: Map<String, Int> = emptyMap()): Int =
        sections.sumOf { section ->
            when (section) {
                is WidgetSection -> section.ref?.let { it.rowsOverride ?: sizes[it.widgetId] ?: 2 }
                    ?: section.widget?.size?.default ?: 2
                is StackSection -> maxOf(
                    section.widgets.maxOfOrNull { it.size.default } ?: 0,
                    section.refs.maxOfOrNull { it.rowsOverride ?: sizes[it.widgetId] ?: 2 } ?: 0,
                )
                is HtmlSection -> section.rows
                else -> 0
            }
        }

    /** What a widget draws: title, components, card padding — never less than its default rows. */
    fun estimateWidgetRows(spec: WidgetSpec, rowsOverride: Int? = null): Int {
        // Rows are 48dp units, measured against the phone: the card title is ~28dp, a body line ~24dp,
        // a full-width button 40dp, card padding ~24dp. Halves stay inside so short text cards stop
        // counted double and spilling pages; the total still rounds up, and the default is a floor.
        fun component(component: WidgetComponent): Double = when (component) {
            is WidgetComponent.Text -> {
                val large = component.style?.emphasis == Emphasis.LARGE
                val perLine = if (large) 0.75 else 0.5
                val charsPerLine = if (large) 25 else 40
                val text = component.text
                if (text == null) {
                    if (component.binding == null) 0.0 else perLine
                } else {
                    ((text.length + charsPerLine - 1) / charsPerLine).coerceIn(1, MAX_TEXT_LINES) * perLine
                }
            }
            is WidgetComponent.Counter -> 2.0
            is WidgetComponent.Checklist -> (component.items.size + 1).toDouble()
            is WidgetComponent.Timer -> 2.0
            is WidgetComponent.Progress -> 1.5
            is WidgetComponent.Chart -> 2.0
            is WidgetComponent.Button -> 1.0
            is WidgetComponent.Image -> 1.0
            is WidgetComponent.ListRows -> 3.0
            is WidgetComponent.Gauge -> 2.0
            is WidgetComponent.Globe -> 3.0
            is WidgetComponent.Row -> component.children.maxOfOrNull(::component) ?: 0.0
            is WidgetComponent.Column -> component.children.sumOf(::component)
            is WidgetComponent.Video -> 4.0
            is WidgetComponent.Snapshot -> 3.0
            is WidgetComponent.WebValue -> 1.0
        }
        val content = (if (spec.title != null) 0.5 else 0.0) + spec.components.sumOf(::component) + 0.5
        return maxOf(kotlin.math.ceil(content).toInt(), rowsOverride ?: spec.size.default)
    }

    /** A fresh overflow page: "Home 2", "Home 3"… beside home pages, "Page N" anywhere else. */
    internal fun newPage(doc: LayoutDoc): Page {
        val taken = doc.pages.map { it.id }.toSet()
        val stem = if (taken.any { it == "home" || it.startsWith("home-") }) "home" else "page"
        var n = 2
        while ("$stem-$n" in taken) n++
        val title = (if (stem == "home") "Home" else "Page") + " $n"
        return Page("$stem-$n", title = title, sections = emptyList())
    }

    /** One library widget placement: the page and section holding its ref. */
    data class WidgetPlacement(val pageId: String, val sectionId: String)

    /** Every place [widgetId] sits on home, by ref (or by an inline spec not yet lifted). */
    fun widgetPlacements(doc: LayoutDoc, widgetId: String): List<WidgetPlacement> =
        doc.pages.flatMap { page ->
            page.sections.mapNotNull { section ->
                val holds = when (section) {
                    is WidgetSection -> section.ref?.widgetId == widgetId || section.widget?.id == widgetId
                    is StackSection -> widgetId in section.widgetIds
                    else -> false
                }
                if (holds) listOf(WidgetPlacement(page.id, section.id))
                else if (section is CellGridSection) section.cells.filter { it.holdsWidget(widgetId) }.map { WidgetPlacement(page.id, it.id) }
                else emptyList()
            }.flatten()
        }

    private fun Cell.holdsWidget(widgetId: String) = when (val c = content) {
        is CellContent.WidgetCell -> c.ref.widgetId == widgetId
        is CellContent.StackCell -> c.refs.any { it.widgetId == widgetId }
        else -> false
    }

    // ---- cell grids (plan docs/plans/home-cell-grid.md) ----

    /**
     * [keep] decides per widget id; cells whose widget goes are removed (a stack keeps the rest, an
     * emptied one goes) and the grid closes up. Null when nothing changed.
     */
    private fun LayoutDoc.stripCellWidgets(keep: (String) -> Boolean): LayoutDoc? {
        var changed = false
        val next = mapGrids { grid ->
            val cells = grid.cells.mapNotNull { cell ->
                when (val c = cell.content) {
                    is CellContent.WidgetCell -> if (keep(c.ref.widgetId)) cell else null.also { changed = true }
                    is CellContent.StackCell -> {
                        val refs = c.refs.filter { keep(it.widgetId) }
                        when {
                            refs.size == c.refs.size -> cell
                            refs.isEmpty() -> null.also { changed = true }
                            else -> cell.copy(content = c.copy(refs = refs)).also { changed = true }
                        }
                    }
                    else -> cell
                }
            }
            if (cells.size == grid.cells.size && cells == grid.cells) grid else CellGrid.compact(grid.copy(cells = cells))
        }
        return if (changed) next else null
    }

    /** Cell grid rows a widget of [rows] 48 dp rows takes. */
    fun cellsHigh(rows: Int): Int =
        ((rows + CellLift.ROWS_PER_CELL - 1) / CellLift.ROWS_PER_CELL).coerceAtLeast(1)

    /** Applies [transform] to every grid; a grid it empties goes, like an emptied stack. */
    private fun LayoutDoc.mapGrids(transform: (CellGridSection) -> CellGridSection): LayoutDoc =
        copy(
            pages = pages.map { page ->
                page.copy(
                    sections = page.sections.mapNotNull { section ->
                        if (section !is CellGridSection) section
                        else transform(section).takeUnless { it.cells.isEmpty() && section.cells.isNotEmpty() }
                    },
                )
            },
        )

    private fun LayoutDoc.gridHolding(cellId: String): Pair<Page, CellGridSection>? =
        pages.firstNotNullOfOrNull { page ->
            page.sections.filterIsInstance<CellGridSection>().firstOrNull { g -> g.cells.any { it.id == cellId } }?.let { page to it }
        }

    /** True when [id] is a cell on some grid (menus pass cell ids where they used to pass section ids). */
    fun isCell(doc: LayoutDoc, id: String): Boolean = doc.gridHolding(id) != null

    /**
     * Adds [cell] to [pageId]'s grid at its first free spot, creating the grid (above the dock) when
     * the page has none. The page must exist.
     */
    fun addCell(doc: LayoutDoc, pageId: String, cell: Cell, columns: Int = 4): LayoutDoc =
        doc.copy(
            pages = doc.pages.map { page ->
                if (page.id != pageId) return@map page
                val grid = page.sections.filterIsInstance<CellGridSection>().firstOrNull()
                if (grid != null) {
                    page.copy(sections = page.sections.map { if (it.id == grid.id) CellGrid.add(grid, cell.copy(x = 0, y = 0)) else it })
                } else {
                    val taken = doc.pages.flatMap { it.sections }.map { it.id }.toSet()
                    var id = "cells-$pageId"
                    var n = 2
                    while (id in taken) id = "cells-$pageId-${n++}"
                    val created = CellGrid.add(CellGridSection(id, columns, emptyList()), cell.copy(x = 0, y = 0))
                    val dock = page.sections.indexOfFirst { it is DockSection }
                    val sections = page.sections.toMutableList().apply { if (dock < 0) add(created) else add(dock, created) }
                    page.copy(sections = sections)
                }
            },
        )

    /** Grid rows (as 48 dp rows) adding [cell] to [pageId] costs: zero when it fits beside others. */
    private fun addedRows(doc: LayoutDoc, pageId: String, cell: Cell): Int {
        val page = doc.pages.firstOrNull { it.id == pageId } ?: return 0
        val before = page.sections.filterIsInstance<CellGridSection>().firstOrNull()?.rows ?: 0
        val after = addCell(doc, pageId, cell).pages.first { it.id == pageId }.sections.filterIsInstance<CellGridSection>().first().rows
        return (after - before) * CellLift.ROWS_PER_CELL
    }

    /** Moves cell [cellId] to ([x], [y]) on its grid; cells in the way go down. Null when it isn't a cell. */
    fun moveCell(doc: LayoutDoc, cellId: String, x: Int, y: Int): LayoutDoc? {
        val (_, grid) = doc.gridHolding(cellId) ?: return null
        return doc.mapGrids { if (it.id == grid.id) CellGrid.move(it, cellId, x, y) else it }
    }

    /** Resizes cell [cellId] to [w] × [h] cells; cells in the way go down. Null when it isn't a cell. */
    fun resizeCell(doc: LayoutDoc, cellId: String, w: Int, h: Int): LayoutDoc? {
        val (_, grid) = doc.gridHolding(cellId) ?: return null
        return doc.mapGrids { if (it.id == grid.id) CellGrid.resize(it, cellId, w, h) else it }
    }

    /**
     * The menu's Move up / down / to page for a cell: [steps] rows up or down on its grid (pushing
     * what is there), or onto [targetPageId]'s grid at its first free spot. Null when nothing moves.
     */
    fun moveCellBy(doc: LayoutDoc, cellId: String, steps: Int = 0, targetPageId: String? = null): LayoutDoc? {
        val (page, grid) = doc.gridHolding(cellId) ?: return null
        val cell = grid.cells.first { it.id == cellId }
        if (targetPageId != null && targetPageId != page.id) {
            if (doc.pages.none { it.id == targetPageId }) return null
            val removed = doc.mapGrids { if (it.id == grid.id) CellGrid.compact(CellGrid.remove(it, cellId)) else it }
            return addCell(removed, targetPageId, cell)
        }
        val y = (cell.y + steps).coerceAtLeast(0)
        if (y == cell.y) return null
        return doc.mapGrids { if (it.id == grid.id) CellGrid.compact(CellGrid.move(it, cellId, cell.x, y)) else it }
    }

    /**
     * Puts [widgetId] on home as a new [WidgetSection] holding a ref: the requested page when the
     * rendered content still fits one screenful, else the first page that does, else a new page.
     * Null when the id is blank or the requested page isn't there. [specs] is the widget library,
     * for rendered heights.
     */
    fun placeWidget(
        doc: LayoutDoc,
        widgetId: String,
        rowsOverride: Int? = null,
        pageId: String? = null,
        specs: Map<String, WidgetSpec> = emptyMap(),
    ): Placed? {
        if (widgetId.isBlank() || doc.pages.isEmpty()) return null
        val spec = specs[widgetId]
        val height = if (spec == null) rowsOverride ?: 2 else estimateWidgetRows(spec, rowsOverride)
        val target = pageId?.let { id -> doc.pages.firstOrNull { it.id == id } } ?: doc.pages.first()
        if (pageId != null && doc.pages.none { it.id == pageId }) return null
        val taken0 = doc.allIdsInDoc()
        val cellId = freeId("widget-$widgetId", taken0)
        // A cell, so a narrow widget sits beside others (plan docs/plans/home-cell-grid.md, step 3).
        val cell = Cell(cellId, 0, 0, spec?.size?.defaultW ?: 4, cellsHigh(height), CellContent.WidgetCell(WidgetRef(widgetId, rowsOverride)))
        val fits = { p: Page -> widgetRowsUsed(doc, p.id, specs) + addedRows(doc, p.id, cell) <= LayoutChecker.MAX_PAGE_ROWS }
        val page = if (fits(target)) target else doc.pages.firstOrNull(fits)
        var placed = doc
        val at = if (page != null) {
            page.id
        } else {
            val created = newPage(doc)
            placed = doc.copy(pages = doc.pages + created)
            created.id
        }
        return Placed(addCell(placed, at, cell), at, cellId)
    }

    private fun LayoutDoc.allIdsInDoc(): Set<String> = pages.allIds()

    private fun freeId(stem: String, taken: Set<String>): String {
        if (stem !in taken) return stem
        var n = 2
        while ("$stem-$n" in taken) n++
        return "$stem-$n"
    }

    /**
     * Takes [widgetId] off home wherever it sits: whole widget sections go, stack refs are stripped
     * (an emptied stack goes too). Null when it wasn't placed.
     */
    fun removeWidgetRefs(doc: LayoutDoc, widgetId: String): LayoutDoc? {
        var changed = false
        val next = doc.copy(
            pages = doc.pages.map { page ->
                page.copy(
                    sections = page.sections.mapNotNull { section ->
                        when (section) {
                            is WidgetSection ->
                                if (section.ref?.widgetId == widgetId || section.widget?.id == widgetId) {
                                    changed = true
                                    null
                                } else section
                            is StackSection -> {
                                val keepWidgets = section.widgets.filterNot { it.id == widgetId }
                                val keepRefs = section.refs.filterNot { it.widgetId == widgetId }
                                if (keepWidgets.size == section.widgets.size && keepRefs.size == section.refs.size) section
                                else {
                                    changed = true
                                    if (section.refs.isNotEmpty() && keepRefs.isEmpty()) null
                                    else if (section.refs.isEmpty() && keepWidgets.isEmpty()) null
                                    else section.copy(widgets = keepWidgets, refs = keepRefs)
                                }
                            }
                            else -> section
                        }
                    },
                )
            },
        )
        val stripped = next.stripCellWidgets { it != widgetId }
        return stripped ?: if (changed) next else null
    }

    /**
     * Drops every ref to a widget the library no longer has (repair after an undo that restored a
     * deleted widget's placement): whole widget sections go, stack refs are stripped, an emptied
     * stack goes too. Null when nothing dangles.
     */
    fun stripUnknownWidgets(doc: LayoutDoc, knownIds: Set<String>): LayoutDoc? {
        var changed = false
        val next = doc.copy(
            pages = doc.pages.map { page ->
                page.copy(
                    sections = page.sections.mapNotNull { section ->
                        when (section) {
                            is WidgetSection -> {
                                val id = section.ref?.widgetId ?: section.widget?.id
                                if (id != null && id !in knownIds) {
                                    changed = true
                                    null
                                } else section
                            }
                            is StackSection -> {
                                val keepWidgets = section.widgets.filter { it.id in knownIds }
                                val keepRefs = section.refs.filter { it.widgetId in knownIds }
                                if (keepWidgets.size == section.widgets.size && keepRefs.size == section.refs.size) section
                                else {
                                    changed = true
                                    if (keepWidgets.isEmpty() && keepRefs.isEmpty()) null
                                    else section.copy(widgets = keepWidgets, refs = keepRefs)
                                }
                            }
                            else -> section
                        }
                    },
                )
            },
        )
        val stripped = next.stripCellWidgets { it in knownIds }
        return stripped ?: if (changed) next else null
    }

    /** Sets [rows] as the per-placement height override on every ref of [widgetId]. Null when none. */
    fun resizeWidget(doc: LayoutDoc, widgetId: String, rows: Int): LayoutDoc? {
        var changed = false
        fun refs(refs: List<WidgetRef>): List<WidgetRef> =
            refs.map { if (it.widgetId == widgetId) { changed = true; it.copy(rowsOverride = rows) } else it }
        val next = doc.copy(
            pages = doc.pages.map { page ->
                page.copy(
                    sections = page.sections.map { section ->
                        when (section) {
                            is WidgetSection ->
                                section.ref?.takeIf { it.widgetId == widgetId }?.let { section.copy(ref = it.copy(rowsOverride = rows)).also { changed = true } }
                                    ?: section
                            is StackSection ->
                                if (section.refs.isNotEmpty() && section.refs.any { it.widgetId == widgetId }) {
                                    section.copy(refs = refs(section.refs))
                                } else section
                            else -> section
                        }
                    },
                )
            },
        )
        // A cell's height is its span: the rows become whole cells, pushing what is below.
        val cells = next.pages.flatMap { it.sections }.filterIsInstance<CellGridSection>().flatMap { g -> g.cells.filter { it.holdsWidget(widgetId) } }
        if (cells.isEmpty()) return if (changed) next else null
        return cells.fold(next) { d, cell ->
            val withRef = d.mapGrids { g ->
                g.copy(cells = g.cells.map { c ->
                    if (c.id != cell.id) c else when (val content = c.content) {
                        is CellContent.WidgetCell -> c.copy(content = content.copy(ref = content.ref.copy(rowsOverride = rows)))
                        is CellContent.StackCell -> c.copy(content = content.copy(refs = content.refs.map { if (it.widgetId == widgetId) it.copy(rowsOverride = rows) else it }))
                        else -> c
                    }
                })
            }
            resizeCell(withRef, cell.id, cell.w, cellsHigh(rows)) ?: withRef
        }
    }

    // ---- HTML widgets (plan docs/plans/widget-maker.md §3.3) ----

    /** Every place a mini app sits on home at widget size. */
    fun htmlPlacements(doc: LayoutDoc, appId: String): List<WidgetPlacement> =
        doc.pages.flatMap { page ->
            page.sections.flatMap { section ->
                when {
                    section is HtmlSection && section.appId == appId -> listOf(WidgetPlacement(page.id, section.id))
                    section is CellGridSection -> section.cells
                        .filter { (it.content as? CellContent.HtmlCell)?.appId == appId }
                        .map { WidgetPlacement(page.id, it.id) }
                    else -> emptyList()
                }
            }
        }

    /**
     * Shows a mini app on home at widget size: the requested page when it has room, else the
     * first page that does, else a new page (same one-screenful rule as [placeWidget]). Null when
     * the id is blank or the requested page isn't there.
     */
    fun placeHtml(
        doc: LayoutDoc,
        appId: String,
        rows: Int = 2,
        pageId: String? = null,
        specs: Map<String, WidgetSpec> = emptyMap(),
    ): Placed? {
        if (appId.isBlank() || doc.pages.isEmpty()) return null
        val target = pageId?.let { id -> doc.pages.firstOrNull { it.id == id } } ?: doc.pages.first()
        if (pageId != null && doc.pages.none { it.id == pageId }) return null
        val cellId = freeId("html-$appId", doc.allIdsInDoc())
        val cell = Cell(cellId, 0, 0, 4, cellsHigh(rows), CellContent.HtmlCell(appId))
        val fits = { p: Page -> widgetRowsUsed(doc, p.id, specs) + addedRows(doc, p.id, cell) <= LayoutChecker.MAX_PAGE_ROWS }
        val page = if (fits(target)) target else doc.pages.firstOrNull(fits)
        var next = doc
        val at = if (page != null) {
            page.id
        } else {
            val created = newPage(doc)
            next = doc.copy(pages = doc.pages + created)
            created.id
        }
        return Placed(addCell(next, at, cell), at, cellId)
    }

    /** Takes a mini app off home wherever it sits at widget size. Null when it wasn't placed. */
    fun removeHtmlApp(doc: LayoutDoc, appId: String): LayoutDoc? {
        var changed = false
        val next = doc.copy(
            pages = doc.pages.map { page ->
                page.copy(
                    sections = page.sections.filterNot { section ->
                        (section is HtmlSection && section.appId == appId).also { if (it) changed = true }
                    },
                )
            },
        ).mapGrids { grid ->
            val cells = grid.cells.filterNot { (it.content as? CellContent.HtmlCell)?.appId == appId }
            if (cells.size == grid.cells.size) grid else CellGrid.compact(grid.copy(cells = cells)).also { changed = true }
        }
        return if (changed) next else null
    }

    /**
     * Drops pages left with nothing to show (no items, widgets, HTML or skill widgets — a header
     * or an empty grid alone doesn't keep one), so removing the last thing off a screen removes
     * the screen. Never drops the last page, and never a page a mode patch adds to ([referenced]).
     */
    fun pruneEmptyPages(doc: LayoutDoc, referenced: Set<String> = emptySet()): LayoutDoc? {
        if (doc.pages.size <= 1) return null
        fun shows(section: Section): Boolean = when (section) {
            is ItemSection -> section.items.isNotEmpty()
            is WidgetSection -> section.widget != null || section.ref != null
            is StackSection -> section.widgets.isNotEmpty() || section.refs.isNotEmpty()
            is HtmlSection, is SkillWidgetSection, is DockSection -> true
            is CellGridSection -> section.cells.isNotEmpty()
            else -> false
        }
        val kept = doc.pages.filter { page -> page.id in referenced || page.sections.any(::shows) }
        if (kept.size == doc.pages.size || kept.isEmpty()) return null
        return doc.copy(pages = kept)
    }
}
