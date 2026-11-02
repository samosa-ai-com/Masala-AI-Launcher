package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.Cell
import com.samosaai.masala.core.contract.CellContent
import com.samosaai.masala.core.contract.CellGridSection
import com.samosaai.masala.core.contract.WidgetRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellGridTest {
    private fun cell(id: String, x: Int, y: Int, w: Int = 1, h: Int = 1) =
        Cell(id, x, y, w, h, CellContent.WidgetCell(WidgetRef(id)))

    private fun grid(vararg cells: Cell) = CellGridSection("g", columns = 4, cells = cells.toList())

    private fun CellGridSection.at(id: String) = cells.first { it.id == id }.let { it.x to it.y }

    private fun assertNoOverlap(g: CellGridSection) {
        for (a in g.cells) for (b in g.cells) if (a.id != b.id) assertFalse(CellGrid.overlaps(a, b), "${a.id} overlaps ${b.id}")
    }

    @Test fun twoHalfWidthWidgetsShareARow() {
        val g = CellGrid.add(grid(cell("a", 0, 0, w = 2, h = 2)), cell("b", 0, 0, w = 2, h = 2))
        assertEquals(2 to 0, g.at("b"))
        assertEquals(2, g.rows)
    }

    @Test fun firstFreeSkipsTakenCells() {
        val g = grid(cell("a", 0, 0, w = 4), cell("b", 0, 1, w = 3))
        assertEquals(3 to 1, CellGrid.firstFree(g, 1, 1))
        assertEquals(0 to 2, CellGrid.firstFree(g, 2, 1))
    }

    @Test fun fitsRejectsOutOfBoundsAndOverlap() {
        val g = grid(cell("a", 0, 0, w = 2))
        assertFalse(CellGrid.fits(g, cell("b", 3, 0, w = 2)))
        assertFalse(CellGrid.fits(g, cell("b", 1, 0)))
        assertTrue(CellGrid.fits(g, cell("b", 2, 0, w = 2)))
    }

    @Test fun moveOntoOthersPushesThemDown() {
        val g = grid(cell("a", 0, 0, w = 4, h = 2), cell("b", 0, 2, w = 2), cell("c", 2, 2, w = 2))
        val moved = CellGrid.move(g, "b", 0, 0)
        assertEquals(0 to 0, moved.at("b"))
        assertEquals(0 to 1, moved.at("a"))
        assertNoOverlap(moved)
    }

    @Test fun moveClampsIntoColumns() {
        val g = CellGrid.move(grid(cell("a", 0, 0, w = 2)), "a", 9, 1)
        assertEquals(2 to 1, g.at("a"))
    }

    @Test fun resizePushesNeighboursDown() {
        val g = CellGrid.resize(grid(cell("a", 0, 0, w = 2), cell("b", 2, 0, w = 2)), "a", 4, 1)
        // Pushed straight down: a cell keeps its column.
        assertEquals(2 to 1, g.at("b"))
        assertNoOverlap(g)
    }

    @Test fun compactClosesGapsAndKeepsColumns() {
        val g = CellGrid.compact(CellGrid.remove(grid(cell("a", 0, 0, w = 4), cell("b", 1, 1), cell("c", 0, 3, w = 2)), "a"))
        assertEquals(1 to 0, g.at("b"))
        assertEquals(0 to 1, g.at("c"))
        assertNoOverlap(g)
    }
}
