package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.Cell
import com.samosaai.masala.core.contract.CellGridSection

/**
 * Pure placement on a [CellGridSection]. Cells never overlap;
 * a move or resize that lands on others pushes them down, like a stock launcher (decision D3).
 *
 * Every entry point clamps what it is given (columns, spans, positions), so bad layout data can
 * neither throw nor loop: the checker rejects it, and these never make it worse.
 */
object CellGrid {

    fun overlaps(a: Cell, b: Cell): Boolean = overlapsAt(a, b.x, b.y, b.w, b.h)

    private fun overlapsAt(a: Cell, x: Int, y: Int, w: Int, h: Int): Boolean =
        a.x < x + w && x < a.x + a.w && a.y < y + h && y < a.y + a.h

    private fun columnsOf(grid: CellGridSection) = grid.columns.coerceAtLeast(1)

    /** [cell] is inside the columns and clear of every other cell. */
    fun fits(grid: CellGridSection, cell: Cell): Boolean =
        cell.x >= 0 && cell.y >= 0 && cell.w >= 1 && cell.h >= 1 && cell.x + cell.w <= grid.columns &&
            grid.cells.none { it.id != cell.id && overlaps(it, cell) }

    /**
     * The first free top-left for a [w] × [h] block, reading rows top to bottom, left to right. Always
     * found by the row below the lowest cell, so the search is bounded.
     */
    fun firstFree(grid: CellGridSection, w: Int, h: Int): Pair<Int, Int> {
        val columns = columnsOf(grid)
        val width = w.coerceIn(1, columns)
        val height = h.coerceAtLeast(1)
        for (y in 0..grid.rows) {
            for (x in 0..columns - width) {
                if (grid.cells.none { overlapsAt(it, x, y, width, height) }) return x to y
            }
        }
        return 0 to grid.rows
    }

    /** Adds [cell] at its own spot if free, otherwise at the first free spot of its size. */
    fun add(grid: CellGridSection, cell: Cell): CellGridSection {
        val columns = columnsOf(grid)
        val w = cell.w.coerceIn(1, columns)
        val sized = cell.copy(w = w, h = cell.h.coerceAtLeast(1), x = cell.x.coerceIn(0, columns - w), y = cell.y.coerceIn(0, grid.rows))
        val placed = if (fits(grid, sized)) sized else firstFree(grid, w, sized.h).let { (x, y) -> sized.copy(x = x, y = y) }
        return grid.copy(cells = grid.cells + placed)
    }

    /**
     * Moves [cellId] to ([x], [y]), clamped into the columns and to just below the other cells (a
     * drop far down the screen lands on the next row, not twenty empty rows lower); cells in the way
     * are pushed down.
     */
    fun move(grid: CellGridSection, cellId: String, x: Int, y: Int): CellGridSection {
        val cell = grid.cells.firstOrNull { it.id == cellId } ?: return grid
        val w = cell.w.coerceIn(1, columnsOf(grid))
        val below = grid.cells.filter { it.id != cellId }.maxOfOrNull { it.y + it.h } ?: 0
        val moved = cell.copy(w = w, x = x.coerceIn(0, columnsOf(grid) - w), y = y.coerceIn(0, below))
        return settle(grid, moved)
    }

    /** Resizes [cellId] to [w] × [h] (clamped into the columns); cells in the way are pushed down. */
    fun resize(grid: CellGridSection, cellId: String, w: Int, h: Int): CellGridSection {
        val cell = grid.cells.firstOrNull { it.id == cellId } ?: return grid
        val x = cell.x.coerceIn(0, columnsOf(grid) - 1)
        val width = w.coerceIn(1, columnsOf(grid) - x)
        return settle(grid, cell.copy(x = x, w = width, h = h.coerceAtLeast(1)))
    }

    fun remove(grid: CellGridSection, cellId: String): CellGridSection =
        grid.copy(cells = grid.cells.filterNot { it.id == cellId })

    /**
     * Pulls every cell up as far as it goes, keeping columns and the top-to-bottom order, so a
     * removal or a push leaves no empty rows behind. Jumps from obstacle to obstacle instead of
     * stepping row by row, so a far-down cell costs no more than a near one.
     */
    fun compact(grid: CellGridSection): CellGridSection {
        val placed = mutableListOf<Cell>()
        for (cell in grid.cells.sortedWith(compareBy({ it.y }, { it.x }))) {
            var y = placed.filter { it.x < cell.x + cell.w && cell.x < it.x + it.w && it.y + it.h <= cell.y }
                .maxOfOrNull { it.y + it.h } ?: 0
            while (true) {
                val hit = placed.firstOrNull { overlapsAt(it, cell.x, y, cell.w, cell.h) } ?: break
                y = hit.y + hit.h
            }
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
}
