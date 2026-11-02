package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.CellContent
import com.samosaai.masala.core.contract.CellGridSection
import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.HeaderSection
import com.samosaai.masala.core.contract.HtmlSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.migration.CellLift
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CellLiftTest {
    private val tall = WidgetSpec("tall", components = listOf(WidgetComponent.Text(text = "x")), size = SizeRange(1, 4, 4))

    @Test fun aRunOfWidgetsBecomesOneGridStackedFullWidth() {
        val doc = LayoutDoc(
            pages = listOf(
                Page(
                    "home",
                    sections = listOf(
                        HeaderSection("h"),
                        WidgetSection("w1", ref = WidgetRef("tall")),
                        WidgetSection("w2", ref = WidgetRef("other", rowsOverride = 3)),
                        HtmlSection("x", appId = "app", rows = 2),
                        GridSection("apps", items = emptyList()),
                        DockSection("dock", items = emptyList()),
                    ),
                ),
            ),
        )
        val folded = CellLift.fold(doc, mapOf("tall" to tall))!!
        val sections = folded.pages[0].sections
        assertEquals(listOf("h", "cells-w1", "apps", "dock"), sections.map { it.id })
        val grid = assertIs<CellGridSection>(sections[1])
        assertEquals(listOf("w1", "w2", "x"), grid.cells.map { it.id })
        assertEquals(listOf(0, 2, 4), grid.cells.map { it.y })
        assertEquals(listOf(2, 2, 1), grid.cells.map { it.h })
        assertEquals(setOf(4), grid.cells.map { it.w }.toSet())
        assertIs<CellContent.HtmlCell>(grid.cells[2].content)
        assertEquals(emptyList(), LayoutChecker.check(folded, LayoutChecker.Environment(emptyMap(), emptySet())))
    }

    @Test fun nothingToFoldIsNull() {
        val doc = LayoutDoc(pages = listOf(Page("home", sections = listOf(HeaderSection("h"), GridSection("apps", items = emptyList())))))
        assertNull(CellLift.fold(doc, emptyMap()))
    }

    @Test fun anOldLayoutStillDecodes() {
        val json = """{"schemaVersion":3,"pages":[{"id":"home","sections":[{"type":"header","id":"h"}]}]}"""
        val doc = com.samosaai.masala.core.migration.LayoutMigrations.decode(
            com.samosaai.masala.core.contract.MasalaJson.strict.parseToJsonElement(json),
        )
        assertEquals(LayoutDoc.CURRENT_SCHEMA_VERSION, doc.schemaVersion)
    }
}
