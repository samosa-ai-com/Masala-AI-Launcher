package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.AddItem
import com.samosaai.masala.core.contract.AddSection
import com.samosaai.masala.core.contract.Cell
import com.samosaai.masala.core.contract.CellContent
import com.samosaai.masala.core.contract.CellGridSection
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.HeaderSection
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.ModePatch
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CellOverlayTest {
    private fun item(id: String) = Item(id, id, IconRef.Symbol("apps"), MasalaAction.OpenDrawer)

    private val grid = CellGridSection(
        "g",
        cells = listOf(
            Cell("clock", 0, 0, 2, 1, CellContent.WidgetCell(WidgetRef("clock"))),
            Cell("gone", 2, 0, 2, 1, CellContent.WidgetCell(WidgetRef("missing"))),
            Cell("c-mail", 0, 1, content = CellContent.ItemCell(item("mail"))),
        ),
    )
    private val base = LayoutDoc(pages = listOf(Page("home", sections = listOf(grid, HeaderSection("after")))))
    private val clock = WidgetSpec("clock", components = listOf(WidgetComponent.Text(text = "12:00")))

    private fun cellsOf(doc: LayoutDoc) = (doc.pages[0].sections.first { it.id == "g" } as CellGridSection).cells

    @Test fun modesHideCellsByCellOrItemIdAndLeaveTheSpot() {
        val merged = OverlayMerge.merge(base, listOf(ModePatch("m", "M", hideSections = listOf("clock"), hideItems = listOf("mail")))).layout
        assertEquals(listOf("gone"), cellsOf(merged).map { it.id })
        assertEquals(2, cellsOf(merged).single().x)
    }

    @Test fun aModeAddsAnItemToTheFirstFreeCell() {
        val merged = OverlayMerge.merge(base, listOf(ModePatch("m", "M", addItems = listOf(AddItem("g", item("gym")))))).layout
        val added = cellsOf(merged).first { it.id == "gym" }
        assertEquals(1 to 1, added.x to added.y)
    }

    @Test fun addingAfterAFoldedWidgetLandsAfterItsGrid() {
        val merged = OverlayMerge.merge(base, listOf(ModePatch("m", "M", addSections = listOf(AddSection("home", HeaderSection("new"), after = "clock"))))).layout
        assertEquals(listOf("g", "new", "after"), merged.pages[0].sections.map { it.id })
    }

    @Test fun resolvingFillsWidgetsAndDropsMissingOnes() {
        val resolved = LayoutResolver.resolve(base, emptyList(), com.samosaai.masala.core.Fixtures.at("09:00"), mapOf("clock" to clock))
        val cells = cellsOf(resolved.layout)
        assertEquals(listOf("clock", "c-mail"), cells.map { it.id })
        assertEquals(clock, assertIs<CellContent.WidgetCell>(cells[0].content).widget)
        assertEquals(listOf("missing"), resolved.missingWidgets)
    }

    @Test fun aModeCannotAddAnItemWhoseIdACellAlreadyUses() {
        val merged = OverlayMerge.merge(base, listOf(ModePatch("m", "M", addItems = listOf(AddItem("g", item("clock"))))))
        assertEquals(1, merged.conflicts.size)
        assertEquals(3, cellsOf(merged.layout).size)
    }
}
