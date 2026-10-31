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

    /** Without [onto] (no folders yet), a drop on an item's centre is a slot like its edge. */
    fun resolve(x: Float, y: Float, sections: Map<String, Box2>, items: Map<String, List<Pair<String, Box2>>>, dragged: String, onto: Boolean = true): DropTarget? {
        if (onto) for (list in items.values) for ((id, box) in list) {
            if (id != dragged && box.inset(ONTO_INSET).contains(x, y)) return DropTarget.Onto(id)
        }
        val sectionId = sections.entries.firstOrNull { it.value.contains(x, y) }?.key ?: return null
        val others = items[sectionId].orEmpty().filter { it.first != dragged }
        val index = others.count { (_, box) ->
            box.centerY < y - box.height / 2 || (abs(box.centerY - y) <= box.height / 2 && box.centerX < x)
        }
        val ordered = others.sortedWith(compareBy({ it.second.top }, { it.second.left }))
        return DropTarget.Slot(sectionId, index, ordered.getOrNull(index)?.first)
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

    /** Pointer minus the item's top-left at lift, so the lifted copy stays under the finger. */
    var grab: Offset by mutableStateOf(Offset.Zero)
        private set
    var pointer: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Item id → (owning section id, box). */
    val itemBoxes = mutableStateMapOf<String, Pair<String, Box2>>()
    val sectionBoxes = mutableStateMapOf<String, Box2>()

    /** The page on screen, set by the renderer; a drop off the sections of another page moves the item there. */
    var page: String? = null
    private var startPage: String? = null

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
        if (dragging != null) pointer = at
    }

    /** Where a drop at the pointer lands now; resolved only at release, not on every move. */
    fun target(): DropTarget? {
        val item = dragging ?: return null
        val items = itemBoxes.entries.groupBy({ it.value.first }, { it.key to it.value.second })
        return DropTargets.resolve(pointer.x, pointer.y, sectionBoxes.toMap(), items, item.id, onto && item.action !is MasalaAction.OpenFolder)
            ?: page?.takeIf { it != startPage }?.let { DropTarget.Page(it) }
    }

    /** Ends the drag; returns where it landed, or null to cancel. */
    fun drop(): DropTarget? = target().also { cancel() }

    fun cancel() {
        dragging = null
    }
}
