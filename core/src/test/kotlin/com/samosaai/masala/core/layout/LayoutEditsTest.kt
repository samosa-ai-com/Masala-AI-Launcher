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

    // ---- folders ----

    private fun folderOf(doc: LayoutDoc, id: String) =
        doc.pages.flatMap { it.sections }.filterIsInstance<ItemSection>().flatMap { it.items }.first { it.id == id }

    private fun children(doc: LayoutDoc, folderId: String) =
        (folderOf(doc, folderId).action as MasalaAction.OpenFolder).items.map { it.id }

    /** grid: a, f(c, d), b — dock: x, y. */
    private val withFolder = assertNotNull(LayoutEdits.makeFolder(doc, "d", "c", "f", "Folder"))
        .let { assertNotNull(LayoutEdits.moveBefore(it, "b", "grid", null)) }

    @Test
    fun `makeFolder replaces the target in place with both items`() {
        val out = assertNotNull(LayoutEdits.makeFolder(doc, "a", "c", "f", "Work"))
        assertEquals(listOf("b", "f", "d"), ids(out, "grid"))
        assertEquals(listOf("c", "a"), children(out, "f"))
        assertEquals("Work", folderOf(out, "f").label)
        assertEquals(IconRef.Symbol("folder"), folderOf(out, "f").icon)
    }

    @Test
    fun `makeFolder across sections`() {
        val out = assertNotNull(LayoutEdits.makeFolder(doc, "a", "x", "f", "Folder"))
        assertEquals(listOf("b", "c", "d"), ids(out, "grid"))
        assertEquals(listOf("f", "y"), ids(out, "dock"))
        assertEquals(listOf("x", "a"), children(out, "f"))
    }

    @Test
    fun `makeFolder with a folder, the same item or an unknown item is null`() {
        assertNull(LayoutEdits.makeFolder(withFolder, "a", "f", "g", "Folder"))
        assertNull(LayoutEdits.makeFolder(withFolder, "f", "a", "g", "Folder"))
        assertNull(LayoutEdits.makeFolder(doc, "a", "a", "g", "Folder"))
        assertNull(LayoutEdits.makeFolder(doc, "nope", "a", "g", "Folder"))
        assertNull(LayoutEdits.makeFolder(doc, "a", "nope", "g", "Folder"))
    }

    @Test
    fun `addToFolder appends and removes the item from where it was`() {
        val out = assertNotNull(LayoutEdits.addToFolder(withFolder, "x", "f"))
        assertEquals(listOf("c", "d", "x"), children(out, "f"))
        assertEquals(listOf("y"), ids(out, "dock"))
    }

    @Test
    fun `addToFolder null cases`() {
        assertNull(LayoutEdits.addToFolder(withFolder, "a", "b"), "target is not a folder")
        assertNull(LayoutEdits.addToFolder(withFolder, "f", "f"), "a folder into a folder")
        assertNull(LayoutEdits.addToFolder(withFolder, "nope", "f"), "unknown item")
        val full = (1..LayoutChecker.MAX_FOLDER_ITEMS).map { item("c$it") }
        val fullDoc = LayoutDoc(
            pages = listOf(
                Page(
                    "home",
                    sections = listOf(
                        GridSection(
                            "grid",
                            items = listOf(item("a"), Item("f", "Folder", IconRef.Symbol("folder"), MasalaAction.OpenFolder(full))),
                        ),
                    ),
                ),
            ),
        )
        assertNull(LayoutEdits.addToFolder(fullDoc, "a", "f"), "full folder")
    }

    @Test
    fun `moveOutOfFolder puts the child right after the folder`() {
        val three = assertNotNull(LayoutEdits.addToFolder(withFolder, "x", "f"))
        val out = assertNotNull(LayoutEdits.moveOutOfFolder(three, "f", "d"))
        assertEquals(listOf("a", "f", "d", "b"), ids(out, "grid"))
        assertEquals(listOf("c", "x"), children(out, "f"))
    }

    @Test
    fun `moveOutOfFolder with one child left replaces the folder with it`() {
        val out = assertNotNull(LayoutEdits.moveOutOfFolder(withFolder, "f", "c"))
        assertEquals(listOf("a", "d", "c", "b"), ids(out, "grid"))
    }

    @Test
    fun `moveOutOfFolder null cases`() {
        assertNull(LayoutEdits.moveOutOfFolder(withFolder, "f", "a"), "not a child")
        assertNull(LayoutEdits.moveOutOfFolder(withFolder, "a", "c"), "not a folder")
        assertNull(LayoutEdits.moveOutOfFolder(withFolder, "nope", "c"), "unknown folder")
    }

    @Test
    fun `renameFolder`() {
        assertEquals("Games", folderOf(assertNotNull(LayoutEdits.renameFolder(withFolder, "f", "Games")), "f").label)
        assertNull(LayoutEdits.renameFolder(withFolder, "f", "  "), "blank")
        assertNull(LayoutEdits.renameFolder(withFolder, "a", "Games"), "not a folder")
    }

    @Test
    fun `dissolveFolder puts the children in its place, in order`() {
        val out = assertNotNull(LayoutEdits.dissolveFolder(withFolder, "f"))
        assertEquals(listOf("a", "c", "d", "b"), ids(out, "grid"))
        assertNull(LayoutEdits.dissolveFolder(withFolder, "a"))
        assertNull(LayoutEdits.dissolveFolder(withFolder, "nope"))
    }
}
