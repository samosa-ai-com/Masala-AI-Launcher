package com.samosaai.masala.ui.home

import androidx.compose.ui.geometry.Offset
import com.samosaai.masala.core.contract.Cell
import com.samosaai.masala.core.contract.CellContent
import com.samosaai.masala.core.contract.WidgetRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Cell drags snap the card's top-left to the nearest cell, inside the columns. */
class HomeDragCellTest {
    // 4 columns of 180 px, rows of 240 px, grid starting at (0, 1000).
    private val grid = GridGeom(Box2(0f, 1000f, 720f, 1720f), 4, 180f, 240f)
    private val wide = Cell("w", 0, 0, 2, 1, CellContent.WidgetCell(WidgetRef("w")))

    @Test fun `snap rounds to the nearest cell and stays inside the columns`() {
        assertEquals(1 to 0, CellSnap.at(170f, 1010f, grid, 2))
        assertEquals(2 to 1, CellSnap.at(700f, 1250f, grid, 2))
        assertEquals(0 to 0, CellSnap.at(-90f, 900f, grid, 2))
    }

    @Test fun `the card follows the grab point and drops where it shows`() {
        val d = HomeDragState().also { it.grids["g"] = grid }
        // Grabbed 50 px into the card; move right by one and a half columns and down a row.
        d.startCell(wide, "g", Offset(50f, 1050f))
        d.moveTo(Offset(50f + 280f, 1050f + 240f))
        assertEquals(CellDrop("g", 2, 1), d.cellTarget())
        assertEquals(CellDrop("g", 2, 1), d.dropCell())
        assertNull(d.draggingCell)
    }

    @Test fun `dropping back on its own spot is no move`() {
        val d = HomeDragState().also { it.grids["g"] = grid }
        d.startCell(wide, "g", Offset(50f, 1050f))
        d.moveTo(Offset(60f, 1060f))
        assertNull(d.dropCell())
    }
}
