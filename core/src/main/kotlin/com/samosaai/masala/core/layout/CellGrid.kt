package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.Cell
import com.samosaai.masala.core.contract.CellGridSection

/**
 * Pure placement on a [CellGridSection] (plan `docs/plans/home-cell-grid.md`). Cells never overlap;
 * a move or resize that lands on others pushes them down, like a stock launcher (decision D3).
 */
object CellGrid {

    fun overlaps(a: Cell, b: Cell): Boolean =
        a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    /** [cell] is inside the columns and clear of every other cell. */
    fun fits(grid: CellGridSection, cell: Cell): Boolean =
        cell.x >= 0 && cell.y >= 0 && cell.w >= 1 && cell.h >= 1 && cell.x + cell.w <= grid.columns &&
            grid.cells.none { it.id != cell.id && overlaps(it, cell) }

    /** The first free top-left for a [w] × [h] block, reading rows top to bottom, left to right. */
    fun firstFree(grid: CellGridSection, w: Int, h: Int): Pair<Int, Int> {
        val width = w.coerceIn(1, grid.columns)
        var y = 0
        while (true) {
            for (x in 0..grid.columns - width) {
                val probe = Cell("\u0000probe", x, y, width, h, PROBE)
                if (fits(grid, probe)) return x to y
            }
            y++
        }
    }

    /** Adds [cell] at its own spot if free, otherwise at the first free spot of its size. */
    fun add(grid: CellGridSection, cell: Cell): CellGridSection {
        val w = cell.w.coerceIn(1, grid.columns)
        val sized = cell.copy(w = w, x = cell.x.coerceIn(0, grid.columns - w))
        val placed = if (fits(grid, sized)) sized else firstFree(grid, w, sized.h).let { (x, y) -> sized.copy(x = x, y = y) }
        return grid.copy(cells = grid.cells + placed)
    }

    /** Moves [cellId] to ([x], [y]) (clamped into the columns); cells in the way are pushed down. */
    fun move(grid: CellGridSection, cellId: String, x: Int, y: Int): CellGridSection {
        val cell = grid.cells.firstOrNull { it.id == cellId } ?: return grid
        val moved = cell.copy(x = x.coerceIn(0, grid.columns - cell.w), y = y.coerceAtLeast(0))
        return settle(grid, moved)
    }

    /** Resizes [cellId] to [w] × [h] (clamped into the columns); cells in the way are pushed down. */
    fun resize(grid: CellGridSection, cellId: String, w: Int, h: Int): CellGridSection {
        val cell = grid.cells.firstOrNull { it.id == cellId } ?: return grid
        val width = w.coerceIn(1, grid.columns - cell.x)
        return settle(grid, cell.copy(w = width, h = h.coerceAtLeast(1)))
    }

    fun remove(grid: CellGridSection, cellId: String): CellGridSection =
        grid.copy(cells = grid.cells.filterNot { it.id == cellId })

    /**
     * Pulls every cell up as far as it goes, keeping columns and the top-to-bottom order, so a
     * removal or a push leaves no empty rows behind.
     */
    fun compact(grid: CellGridSection): CellGridSection {
        val placed = mutableListOf<Cell>()
        for (cell in grid.cells.sortedWith(compareBy({ it.y }, { it.x }))) {
            var y = cell.y
            while (y > 0 && placed.none { overlaps(it, cell.copy(y = y - 1)) }) y--
            placed += cell.copy(y = y)
        }
        return grid.copy(cells = grid.cells.map { c -> placed.first { it.id == c.id } })
    }

    /**
     * [fixed] keeps its spot; every other cell it (or a pushed cell) overlaps moves down just past
     * it, in top-to-bottom order, until nothing overlaps.
     */
    private fun settle(grid: CellGridSection, fixed: Cell): CellGridSection {
        val placed = mutableListOf(fixed)
        val rest = grid.cells.filter { it.id != fixed.id }.sortedWith(compareBy({ it.y }, { it.x }))
        for (cell in rest) {
            var c = cell
            while (true) {
                val hit = placed.firstOrNull { overlaps(it, c) } ?: break
                c = c.copy(y = hit.y + hit.h)
            }
            placed += c
        }
        return grid.copy(cells = grid.cells.map { c -> placed.first { it.id == c.id } })
    }

    private val PROBE = com.samosaai.masala.core.contract.CellContent.HtmlCell("")
}
