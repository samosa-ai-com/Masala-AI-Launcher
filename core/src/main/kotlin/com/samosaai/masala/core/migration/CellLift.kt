package com.samosaai.masala.core.migration

import com.samosaai.masala.core.contract.Cell
import com.samosaai.masala.core.contract.CellContent
import com.samosaai.masala.core.contract.CellGridSection
import com.samosaai.masala.core.contract.HtmlSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.Section
import com.samosaai.masala.core.contract.SkillWidgetSection
import com.samosaai.masala.core.contract.SkillWidgetSize
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.layout.LayoutEdits

/**
 * Folds a layout's widget sections into cell grids (plan `docs/plans/home-cell-grid.md`, step 1):
 * each run of widget, stack, HTML and skill sections becomes one [CellGridSection], one full-width
 * cell per section, stacked in order, so the screen looks as before and can then be rearranged.
 *
 * Cells keep their section's id and rules, so mode patches that hide them still find them. Item
 * sections stay sections (modes add items to them by id). Pure; the repository saves the result.
 * Needs the library to size cells, which is why it isn't a schema migration step.
 */
object CellLift {

    /** Rows (48 dp) per cell row: a cell is an icon cell high. */
    const val ROWS_PER_CELL = 2

    /** The folded layout, or null when there is nothing to fold. */
    fun fold(layout: LayoutDoc, library: Map<String, WidgetSpec>, columns: Int = 4): LayoutDoc? {
        var changed = false
        val taken = layout.pages.flatMap { it.sections }.map { it.id }.toMutableSet()
        val pages = layout.pages.map { page ->
            val out = mutableListOf<Section>()
            var run = mutableListOf<Cell>()
            fun flush() {
                if (run.isEmpty()) return
                // Free against every section id, so a layout that already has a grid can't get a twin.
                var id = "cells-${run.first().id}"
                var n = 2
                while (id in taken) id = "cells-${run.first().id}-${n++}"
                taken += id
                out += CellGridSection(id, columns, run)
                run = mutableListOf()
                changed = true
            }
            for (section in page.sections) {
                val cell = cellFor(section, library, columns, run.sumOf { it.h })
                if (cell == null) {
                    flush()
                    out += section
                } else {
                    run += cell
                }
            }
            flush()
            page.copy(sections = out)
        }
        return if (changed) layout.copy(pages = pages) else null
    }

    private fun cellFor(section: Section, library: Map<String, WidgetSpec>, columns: Int, y: Int): Cell? {
        // What the card draws today (it grows past its default rows), so nothing is cut off after folding.
        fun rowsOf(ref: WidgetRef): Int {
            val spec = library[ref.widgetId] ?: return ref.rowsOverride ?: 2
            return maxOf(LayoutEdits.estimateWidgetRows(spec), ref.rowsOverride ?: spec.size.default)
        }
        val (content, rows) = when (section) {
            // Inline widgets are WidgetLift's to move first; a section with one is left alone.
            is WidgetSection -> (section.ref ?: return null).let { CellContent.WidgetCell(it) to rowsOf(it) }
            is StackSection -> if (section.refs.isEmpty()) return null
                else CellContent.StackCell(section.refs) to section.refs.maxOf(::rowsOf)
            is HtmlSection -> CellContent.HtmlCell(section.appId) to section.rows
            is SkillWidgetSection -> CellContent.SkillCell(section.skillId) to when (section.size) {
                SkillWidgetSize.SMALL -> 2
                SkillWidgetSize.MEDIUM -> 4
                SkillWidgetSize.LARGE -> 6
            }
            else -> return null
        }
        val h = ((rows + ROWS_PER_CELL - 1) / ROWS_PER_CELL).coerceAtLeast(1)
        return Cell(section.id, 0, y, columns, h, content, section.rules)
    }
}
