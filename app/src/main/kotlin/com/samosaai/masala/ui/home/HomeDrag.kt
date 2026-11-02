package com.samosaai.masala.ui.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.MasalaAction
import kotlin.math.abs

/** Where a dragged widget section lands: its own page, before [beforeSectionId] or at its end. */
data class SectionDrop(val pageId: String, val beforeSectionId: String?)

/** Where a dragged grid cell lands: its top-left cell on its own grid. */
data class CellDrop(val gridId: String, val x: Int, val y: Int)

/** A cell grid on screen, in root pixels, so a drag can snap to its cells. */
data class GridGeom(val box: Box2, val columns: Int, val cellWidth: Float, val cellHeight: Float)

/** Snapping a dragged cell to the grid (pure). */
object CellSnap {
    /**
     * The cell whose top-left is nearest the dragged card's top-left ([left], [top]), kept inside the
     * columns for a card [w] cells wide; never above the first row.
     */
    fun at(left: Float, top: Float, grid: GridGeom, w: Int): Pair<Int, Int> {
        val x = kotlin.math.round((left - grid.box.left) / grid.cellWidth).toInt()
        val y = kotlin.math.round((top - grid.box.top) / grid.cellHeight).toInt()
        return x.coerceIn(0, (grid.columns - w).coerceAtLeast(0)) to y.coerceAtLeast(0)
    }
}

/** Where a drop lands. */
sealed interface DropTarget {
    /**
     * Before the [index]th visible item of the section, not counting the dragged one. [before] is that
     * item's id (null at the end); drops are saved by it, since the base doc may hold hidden items.
     */
    data class Slot(val sectionId: String, val index: Int, val before: String? = null) : DropTarget
    data class Onto(val itemId: String) : DropTarget

    /** Anywhere off the item sections of a page other than the one the drag began on. */
    data class Page(val pageId: String) : DropTarget

    /** Dropped outside the folder sheet that started the drag: take the item back out to home. */
    data object OutOfFolder : DropTarget
}

/** A plain rectangle in root pixels, so hit-testing is testable without Compose. */
data class Box2(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    /** This box shrunk by [fraction] of its width and height on each side. */
    fun inset(fraction: Float): Box2 {
        val dx = (right - left) * fraction
        val dy = (bottom - top) * fraction
        return Box2(left + dx, top + dy, right - dx, bottom - dy)
    }
}

/** Hit-testing for a drop (pure). [items] are visible items by section, in display order. */
object DropTargets {
    /** Fraction cut from each side of an item's box; what is left is the "onto" area (centre 60%). */
    private const val ONTO_INSET = 0.2f

    /**
     * Without [onto] (no folders yet), a drop on an item's centre is a slot like its edge. With
     * [forgiving], a drop that misses every section lands in the nearest one: generated layouts leave
     * gaps between sections, and a drag that crosses one shouldn't vanish.
     */
    fun resolve(
        x: Float,
        y: Float,
        sections: Map<String, Box2>,
        items: Map<String, List<Pair<String, Box2>>>,
        dragged: String,
        onto: Boolean = true,
        forgiving: Boolean = true,
    ): DropTarget? {
        if (onto) for (list in items.values) for ((id, box) in list) {
            if (id != dragged && box.inset(ONTO_INSET).contains(x, y)) return DropTarget.Onto(id)
        }
        val sectionId = sections.entries.firstOrNull { it.value.contains(x, y) }?.key
            ?: (if (forgiving) nearest(x, y, sections) else null)
            ?: return null
        val others = items[sectionId].orEmpty().filter { it.first != dragged }
        val index = others.count { (_, box) ->
            box.centerY < y - box.height / 2 || (abs(box.centerY - y) <= box.height / 2 && box.centerX < x)
        }
        val ordered = others.sortedWith(compareBy({ it.second.top }, { it.second.left }))
        return DropTarget.Slot(sectionId, index, ordered.getOrNull(index)?.first)
    }

    /** The section whose box is closest to the point, by square distance to its edges. */
    private fun nearest(x: Float, y: Float, sections: Map<String, Box2>): String? = sections.minByOrNull { (_, box) ->
        val dx = maxOf(box.left - x, x - box.right, 0f)
        val dy = maxOf(box.top - y, y - box.bottom, 0f)
        dx * dx + dy * dy
    }?.key
}

/**
 * Auto-scroll while dragging (pure): how far to move a scroll area this frame, from where the pointer
 * is inside it. Zero outside the bands, so a drag over a dock or a gap scrolls nothing.
 */
object DragScroll {
    /** Signed pixels for this frame: negative scrolls towards the start, positive towards the end. */
    fun delta(pointerY: Float, top: Float, bottom: Float, band: Float, maxPerFrame: Float): Float {
        val height = bottom - top
        if (height <= 0f || band <= 0f || pointerY < top || pointerY > bottom) return 0f
        val b = band.coerceAtMost(height / 2f)
        val depth = when {
            pointerY < top + b -> -(top + b - pointerY) / b
            pointerY > bottom - b -> (pointerY - (bottom - b)) / b
            else -> 0f
        }
        return depth.coerceIn(-1f, 1f) * maxPerFrame
    }
}

/**
 * The item being dragged on home and the boxes a drop can land in. Cells and item sections register
 * their boxes in [itemBoxes] and [sectionBoxes] (root pixels) and remove them when they leave.
 */
@Stable
class HomeDragState(
    /** Whether a drop onto an item is its own target (folders). A dragged folder never drops onto one. */
    private val onto: Boolean = true,
) {
    var dragging: Item? by mutableStateOf(null)
        private set

    /** The widget section being dragged (finger-drag reorder); null when no section drag is live. */
    var draggingSection: String? by mutableStateOf(null)
        private set

    /** Pointer minus the item's top-left at lift, so the lifted copy stays under the finger. */
    var grab: Offset by mutableStateOf(Offset.Zero)
        private set
    var pointer: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Item id → (owning section id, box). */
    val itemBoxes = mutableStateMapOf<String, Pair<String, Box2>>()
    val sectionBoxes = mutableStateMapOf<String, Box2>()
    /** Widget section id → page id, registered with its box; a section drag never leaves its page. */
    val sectionPages = mutableStateMapOf<String, String>()

    /** The page on screen, set by the renderer; a drop off the sections of another page moves the item there. */
    var page: String? = null
    private var startPage: String? = null
    private var startSectionPage: String? = null

    /**
     * A drag inside a surface that is not home (the folder sheet): set to that surface's box. A drop
     * outside it means "take it out of here" instead of a slot.
     */
    var container: Box2? = null

    fun start(item: Item, at: Offset) {
        // A second finger's long press mustn't take over a drag in progress.
        if (dragging != null) return
        val box = itemBoxes[item.id]?.second
        grab = if (box != null) at - Offset(box.left, box.top) else Offset.Zero
        startPage = page
        dragging = item
        moveTo(at)
    }

    fun moveTo(at: Offset) {
        if (dragging != null || draggingSection != null || draggingCell != null) pointer = at
    }

    /** The grid cell being dragged (finger-drag on a cell grid); null when none. */
    var draggingCell: com.samosaai.masala.core.contract.Cell? by mutableStateOf(null)
        private set
    private var cellGrid: String? = null

    /** Grid id → its geometry, registered by the renderer while on screen. */
    val grids = mutableStateMapOf<String, GridGeom>()

    /** Starts dragging [cell] of grid [gridId]; the card's top-left follows the finger at the grab offset. */
    fun startCell(cell: com.samosaai.masala.core.contract.Cell, gridId: String, at: Offset) {
        if (dragging != null || draggingSection != null || draggingCell != null) return
        val grid = grids[gridId] ?: return
        grab = at - Offset(grid.box.left + cell.x * grid.cellWidth, grid.box.top + cell.y * grid.cellHeight)
        cellGrid = gridId
        draggingCell = cell
        moveTo(at)
    }

    /** Where the dragged cell would land now (same grid only; other pages stay in the menu). */
    fun cellTarget(): CellDrop? {
        val cell = draggingCell ?: return null
        val gridId = cellGrid ?: return null
        val grid = grids[gridId] ?: return null
        val (x, y) = CellSnap.at(pointer.x - grab.x, pointer.y - grab.y, grid, cell.w)
        return CellDrop(gridId, x, y)
    }

    /** Ends a cell drag; returns where it landed, or null when it didn't move. */
    fun dropCell(): CellDrop? {
        val cell = draggingCell
        val target = cellTarget()
        cancelCell()
        return target?.takeUnless { cell != null && it.x == cell.x && it.y == cell.y }
    }

    fun cancelCell() {
        draggingCell = null
        cellGrid = null
    }

    /** Where a drop at the pointer lands now: read it on every move to draw the indicator. */
    fun target(): DropTarget? {
        val item = dragging ?: return null
        container?.let { if (!it.contains(pointer.x, pointer.y)) return DropTarget.OutOfFolder }
        // Off the content of another page is a page move, not the nearest slot of the one underneath:
        // the fallback below is for gaps on the page the drag started on.
        val onSection = sectionBoxes.values.any { it.contains(pointer.x, pointer.y) }
        if (!onSection) page?.takeIf { it != startPage }?.let { return DropTarget.Page(it) }
        val items = itemBoxes.entries.groupBy({ it.value.first }, { it.key to it.value.second })
        return DropTargets.resolve(pointer.x, pointer.y, sectionBoxes.toMap(), items, item.id, onto && item.action !is MasalaAction.OpenFolder)
            ?: page?.takeIf { it != startPage }?.let { DropTarget.Page(it) }
    }

    /** Ends the drag; returns where it landed, or null to cancel. */
    fun drop(): DropTarget? = target().also { cancel() }

    fun cancel() {
        dragging = null
        draggingSection = null
        startSectionPage = null
    }

    /**
     * Starts a widget-section drag (same page only; cross-page moves stay in the menu). A second
     * finger never takes over a drag in progress.
     */
    fun startSection(id: String, pageId: String, at: Offset) {
        if (dragging != null || draggingSection != null) return
        val box = sectionBoxes[id]
        grab = if (box != null) at - Offset(box.left, box.top) else Offset.Zero
        startSectionPage = pageId
        draggingSection = id
        moveTo(at)
    }

    /** Where a dragged section would land now: the sibling it sits before, or the page end. */
    fun sectionTarget(): SectionDrop? {
        val id = draggingSection ?: return null
        val pageId = startSectionPage ?: return null
        val ordered = sectionBoxes.entries
            .filter { (sid, _) -> sid != id && sectionPages[sid] == pageId }
            .sortedBy { it.value.top }
        if (ordered.isEmpty()) return null
        val index = ordered.count { it.value.centerY < pointer.y }
        return SectionDrop(pageId, ordered.getOrNull(index)?.key)
    }

    /** Ends a section drag; returns where it landed, or null to cancel. */
    fun dropSection(): SectionDrop? = sectionTarget().also { cancelSection() }

    fun cancelSection() {
        draggingSection = null
        startSectionPage = null
    }
}
