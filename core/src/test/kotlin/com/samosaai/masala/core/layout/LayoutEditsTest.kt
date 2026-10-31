package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.HeaderSection
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.layout.LayoutEdits.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LayoutEditsTest {
    private fun item(id: String) = Item(id, "Label $id", IconRef.Symbol("apps"), MasalaAction.OpenDrawer)
    private fun items(vararg ids: String) = ids.map(::item)

    private val doc = LayoutDoc(
        pages = listOf(
            Page(
                "home",
                sections = listOf(
                    HeaderSection("header"),
                    GridSection("grid", items = items("a", "b", "c", "d")),
                    DockSection("dock", items = items("x", "y")),
                ),
            ),
            Page("two", sections = listOf(GridSection("grid2", items = items("p")))),
            Page("empty", sections = listOf(HeaderSection("header3"))),
        ),
    )

    private fun ids(doc: LayoutDoc, sectionId: String) =
        (doc.pages.flatMap { it.sections }.first { it.id == sectionId } as ItemSection).items.map { it.id }

    @Test
    fun `reorder forward lands before the item at the target index`() {
        val out = assertNotNull(LayoutEdits.move(doc, "a", Slot("grid", 3)))
        assertEquals(listOf("b", "c", "a", "d"), ids(out, "grid"))
    }

    @Test
    fun `reorder backward`() {
        val out = assertNotNull(LayoutEdits.move(doc, "d", Slot("grid", 1)))
        assertEquals(listOf("a", "d", "b", "c"), ids(out, "grid"))
    }

    @Test
    fun `the same position is a no-op`() {
        assertEquals(doc, LayoutEdits.move(doc, "b", Slot("grid", 1)))
        assertEquals(doc, LayoutEdits.move(doc, "b", Slot("grid", 2)))
    }

    @Test
    fun `move across sections`() {
        val out = assertNotNull(LayoutEdits.move(doc, "b", Slot("dock", 1)))
        assertEquals(listOf("a", "c", "d"), ids(out, "grid"))
        assertEquals(listOf("x", "b", "y"), ids(out, "dock"))
    }

    @Test
    fun `move across pages clamps the index`() {
        val out = assertNotNull(LayoutEdits.move(doc, "y", Slot("grid2", 99)))
        assertEquals(listOf("x"), ids(out, "dock"))
        assertEquals(listOf("p", "y"), ids(out, "grid2"))
    }

    @Test
    fun `an unknown item is null`() {
        assertNull(LayoutEdits.move(doc, "nope", Slot("grid", 0)))
    }

    @Test
    fun `a header target is null`() {
        assertNull(LayoutEdits.move(doc, "a", Slot("header", 0)))
    }

    @Test
    fun `moveBefore lands before the named item`() {
        assertEquals(listOf("b", "c", "a", "d"), ids(assertNotNull(LayoutEdits.moveBefore(doc, "a", "grid", "d")), "grid"))
        assertEquals(listOf("a", "d", "b", "c"), ids(assertNotNull(LayoutEdits.moveBefore(doc, "d", "grid", "b")), "grid"))
    }

    @Test
    fun `moveBefore with no or an unknown item goes to the end`() {
        assertEquals(listOf("b", "c", "d", "a"), ids(assertNotNull(LayoutEdits.moveBefore(doc, "a", "grid", null)), "grid"))
        assertEquals(listOf("x", "y", "a"), ids(assertNotNull(LayoutEdits.moveBefore(doc, "a", "dock", "mode-added")), "dock"))
    }

    @Test
    fun `moveBefore into a header is null`() {
        assertNull(LayoutEdits.moveBefore(doc, "a", "header", null))
    }

    @Test
    fun `moveToPage appends to the first non-dock item section`() {
        val out = assertNotNull(LayoutEdits.moveToPage(doc, "x", "two"))
        assertEquals(listOf("p", "x"), ids(out, "grid2"))
        assertEquals(listOf("y"), ids(out, "dock"))
    }

    @Test
    fun `moveToPage to a page with no item section is null`() {
        assertNull(LayoutEdits.moveToPage(doc, "a", "empty"))
    }
}
