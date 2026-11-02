package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.Cell
import com.samosaai.masala.core.contract.CellContent
import com.samosaai.masala.core.contract.CellGridSection
import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.WidgetRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellEditsTest {
    private fun w(id: String, x: Int, y: Int, w: Int = 4, h: Int = 1) = Cell(id, x, y, w, h, CellContent.WidgetCell(WidgetRef(id)))

    private val doc = LayoutDoc(
        pages = listOf(
            Page("home", sections = listOf(CellGridSection("g", cells = listOf(w("a", 0, 0), w("b", 0, 1, w = 2), w("c", 2, 1, w = 2))), DockSection("dock", emptyList()))),
            Page("two", sections = listOf(DockSection("dock2", emptyList()))),
        ),
    )

    private fun cells(d: LayoutDoc, page: Int = 0) = d.pages[page].sections.filterIsInstance<CellGridSection>().single().cells

    @Test fun moveUpPushesTheRowAboveDown() {
        val moved = assertNotNull(LayoutEdits.moveCellBy(doc, "b", steps = -1))
        assertEquals(0 to 0, cells(moved).first { it.id == "b" }.let { it.x to it.y })
        assertEquals(1, cells(moved).first { it.id == "a" }.y)
        assertNull(LayoutEdits.moveCellBy(doc, "a", steps = -1))
    }

    @Test fun moveToAnotherPageCreatesItsGridAboveTheDock() {
        val moved = assertNotNull(LayoutEdits.moveCellBy(doc, "a", targetPageId = "two"))
        assertEquals(listOf("cells-two", "dock2"), moved.pages[1].sections.map { it.id })
        assertEquals(listOf("a"), cells(moved, 1).map { it.id })
        // The old grid closes up.
        assertEquals(0, cells(moved).first { it.id == "b" }.y)
    }

    @Test fun resizingAWidgetRoundsRowsToCellsAndPushes() {
        val resized = assertNotNull(LayoutEdits.resizeWidget(doc, "a", 4))
        assertEquals(2, cells(resized).first { it.id == "a" }.h)
        assertEquals(2, cells(resized).first { it.id == "b" }.y)
        assertEquals(4, (cells(resized).first { it.id == "a" }.content as CellContent.WidgetCell).ref.rowsOverride)
    }

    @Test fun removingTheLastCellDropsTheGrid() {
        var d: LayoutDoc = doc
        for (id in listOf("a", "b", "c")) d = assertNotNull(LayoutEdits.removeWidgetRefs(d, id))
        assertTrue(d.pages[0].sections.none { it is CellGridSection })
        assertEquals(listOf(LayoutEdits.WidgetPlacement("home", "b")), LayoutEdits.widgetPlacements(doc, "b"))
    }
}
