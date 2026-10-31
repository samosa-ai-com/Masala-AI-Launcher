package com.samosaai.masala.ui.home

import androidx.compose.ui.geometry.Offset
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.MasalaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeDragStateTest {
    private fun item(id: String, action: MasalaAction = MasalaAction.OpenDrawer) = Item(id, id, IconRef.Symbol("apps"), action)
    private val folder = item("f", MasalaAction.OpenFolder(listOf(item("c"))))

    /** One grid section with a and f side by side, 100 px cells; the drag starts on page "home". */
    private fun state() = HomeDragState().apply {
        sectionBoxes["grid"] = Box2(0f, 0f, 200f, 100f)
        itemBoxes["a"] = "grid" to Box2(0f, 0f, 100f, 100f)
        itemBoxes["f"] = "grid" to Box2(100f, 0f, 200f, 100f)
        page = "home"
    }

    @Test
    fun `a drop on an item's centre is onto it`() {
        val drag = state()
        drag.start(item("x"), Offset(10f, 10f))
        drag.moveTo(Offset(50f, 50f))
        assertEquals(DropTarget.Onto("a"), drag.drop())
    }

    @Test
    fun `a dragged folder never drops onto an item`() {
        val drag = state()
        drag.start(folder, Offset(150f, 50f))
        drag.moveTo(Offset(50f, 50f))
        assertEquals(DropTarget.Slot("grid", 0, "a"), drag.drop())
    }

    @Test
    fun `off the sections of another page is a page drop, of the starting page a cancel`() {
        val drag = state()
        drag.start(item("x"), Offset(10f, 10f))
        drag.moveTo(Offset(500f, 500f))
        assertNull(drag.target())
        drag.page = "two"
        assertEquals(DropTarget.Page("two"), drag.drop())
    }

    @Test
    fun `a second long press doesn't take over the drag`() {
        val drag = state()
        drag.start(item("x"), Offset(10f, 10f))
        drag.start(item("y"), Offset(150f, 50f))
        assertEquals("x", drag.dragging?.id)
    }
}
