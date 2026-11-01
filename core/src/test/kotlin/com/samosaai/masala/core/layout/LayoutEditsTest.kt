package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.Binding
import com.samosaai.masala.core.contract.BindingSource
import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.HeaderSection
import com.samosaai.masala.core.contract.HtmlSection
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.layout.LayoutEdits.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `moveBeforeVisible skips a mode-added neighbour and lands on the next saved one`() {
        // The screen shows b, a mode's chip, then c; dropping before the chip must mean before c.
        val moved = assertNotNull(LayoutEdits.moveBeforeVisible(doc, "d", "grid", listOf("b", "mode_chip", "c"), 1))
        assertEquals(listOf("a", "b", "d", "c"), ids(moved, "grid"))
    }

    @Test
    fun `moveBeforeVisible ends when nothing saved follows`() {
        val moved = assertNotNull(LayoutEdits.moveBeforeVisible(doc, "a", "grid", listOf("b", "c", "d", "mode_chip"), 3))
        assertEquals(listOf("b", "c", "d", "a"), ids(moved, "grid"))
        // A hidden item in the visible list isn't in the document, so it can't be the insertion point.
        val skipped = assertNotNull(LayoutEdits.moveBeforeVisible(doc, "a", "grid", listOf("b", "hidden"), 1))
        assertEquals(listOf("b", "c", "d", "a"), ids(skipped, "grid"))
    }

    @Test
    fun `moveBeforeVisible needs the item and the section in the document`() {
        assertNull(LayoutEdits.moveBeforeVisible(doc, "a", "mode_section", emptyList(), 0))
        assertNull(LayoutEdits.moveBeforeVisible(doc, "ghost", "grid", listOf("b"), 0))
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

    private fun folderOf(id: String, vararg children: String) =
        Item(id, "Folder $id", IconRef.Symbol("folder"), MasalaAction.OpenFolder(items(*children)))

    private fun withSectionItems(sectionId: String, list: List<Item>) = doc.copy(
        pages = doc.pages.map { page ->
            page.copy(sections = page.sections.map { if (it.id == sectionId && it is ItemSection) it.withItems(list) else it })
        },
    )

    @Test
    fun `itemsWithFolders lists each folder then its children`() {
        val d = withSectionItems("grid", listOf(item("a"), folderOf("f", "b", "c")))
        val sections = d.pages.flatMap { it.sections }.filterIsInstance<ItemSection>().filter { it.id == "grid" }
        assertEquals(listOf("a", "f", "b", "c"), LayoutEdits.itemsWithFolders(sections).map { it.id })
    }

    @Test
    fun `removeWhere takes matches out of folders and keeps the folder while two are left`() {
        val d = withSectionItems("grid", listOf(item("a"), folderOf("f", "b", "c", "d")))
        val out = LayoutEdits.removeWhere(d) { it.id == "c" }
        val folder = (out.pages[0].sections[1] as ItemSection).items[1]
        assertEquals(listOf("a", "f"), ids(out, "grid"))
        assertEquals(listOf("b", "d"), (folder.action as MasalaAction.OpenFolder).items.map { it.id })
    }

    @Test
    fun `removeWhere turns a folder left with one child into that child, and drops an empty one`() {
        val d = withSectionItems("grid", listOf(folderOf("f", "b", "c"), folderOf("g", "x")))
        assertEquals(listOf("c", "g"), ids(LayoutEdits.removeWhere(d) { it.id == "b" }, "grid"))
        assertEquals(listOf("f"), ids(LayoutEdits.removeWhere(d) { it.id == "x" }, "grid"))
    }

    @Test
    fun `removeWhere leaves sections it isn't given, and an unmatched layout equal`() {
        val out = LayoutEdits.removeWhere(doc, { it !is DockSection }) { it.id == "x" || it.id == "a" }
        assertEquals(listOf("b", "c", "d"), ids(out, "grid"))
        assertEquals(listOf("x", "y"), ids(out, "dock"))
        assertEquals(doc, LayoutEdits.removeWhere(doc) { false })
    }

    @Test
    fun `folder names are trimmed and cut to the limit`() {
        val long = "x".repeat(LayoutChecker.MAX_FOLDER_LABEL + 40)
        assertEquals(LayoutChecker.MAX_FOLDER_LABEL, folderOf(assertNotNull(LayoutEdits.renameFolder(withFolder, "f", long)), "f").label.length)
        assertEquals("Games", folderOf(assertNotNull(LayoutEdits.renameFolder(withFolder, "f", "  Games ")), "f").label)
        assertEquals(LayoutChecker.MAX_FOLDER_LABEL, folderOf(assertNotNull(LayoutEdits.makeFolder(doc, "a", "c", "g", long)), "g").label.length)
    }

    @Test
    fun `moveBefore itself is a no-op`() {
        assertEquals(doc, LayoutEdits.moveBefore(doc, "b", "grid", "b"))
    }

    @Test
    fun `moveOutOfFolder of the only child removes the folder`() {
        val one = Item("f", "Folder", IconRef.Symbol("folder"), MasalaAction.OpenFolder(listOf(item("c"))))
        val single = LayoutDoc(pages = listOf(Page("home", sections = listOf(GridSection("grid", items = listOf(item("a"), one, item("b")))))))
        assertEquals(listOf("a", "c", "b"), ids(assertNotNull(LayoutEdits.moveOutOfFolder(single, "f", "c")), "grid"))
    }

    @Test
    fun `widget placements cover refs, stacks and inline specs`() {
        val doc = widgetDoc()
        assertEquals(
            listOf(LayoutEdits.WidgetPlacement("home", "w1"), LayoutEdits.WidgetPlacement("home", "s1")),
            LayoutEdits.widgetPlacements(doc, "water"),
        )
        assertEquals(listOf(LayoutEdits.WidgetPlacement("home", "s1"), LayoutEdits.WidgetPlacement("two", "s2")), LayoutEdits.widgetPlacements(doc, "steps"))
        assertEquals(emptyList(), LayoutEdits.widgetPlacements(doc, "missing"))
    }

    private val waterSpec = WidgetSpec("water", "Water", listOf(WidgetComponent.Counter("n", "N")), SizeRange(1, 1, 2))
    private val stepsSpec = WidgetSpec("steps", null, listOf(WidgetComponent.Text(binding = Binding(BindingSource.BATTERY))), SizeRange(1, 1, 1))
    private val lib = mapOf("water" to waterSpec, "steps" to stepsSpec)

    @Test
    fun `placeWidget appends a ref section with a free id`() {
        val once = assertNotNull(LayoutEdits.placeWidget(widgetDoc(), "habits", specs = lib))
        val section = once.doc.pages.first().sections.last() as WidgetSection
        assertEquals(WidgetRef("habits"), section.ref)
        assertEquals("home", once.pageId)
        val twice = assertNotNull(LayoutEdits.placeWidget(once.doc, "habits", specs = lib))
        val ids = twice.doc.pages.first().sections.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertNull(LayoutEdits.placeWidget(widgetDoc(), ""))
        assertNull(LayoutEdits.placeWidget(widgetDoc(), "habits", pageId = "missing"))
        val other = assertNotNull(LayoutEdits.placeWidget(widgetDoc(), "habits", rowsOverride = 3, pageId = "two", specs = lib))
        assertEquals("two", other.pageId)
        assertEquals(listOf("s2", "widget-habits"), other.doc.pages.last().sections.map { it.id })
        assertEquals(3, (other.doc.pages.last().sections.last() as WidgetSection).ref?.rowsOverride)
    }

    @Test
    fun `placing past the page budget overflows to room, then a new page`() {        val big = WidgetSpec("big", "Big", listOf(WidgetComponent.Counter("n", "N")), SizeRange(1, 1, 2))
        val specs = mapOf("big" to big)
        // Home starts with a 4-item grid with a title: 3 rendered rows.
        var doc = LayoutDoc(pages = listOf(Page("home", sections = listOf(GridSection("g", title = "Apps", columns = 4, items = items("a", "b", "c", "d"))))))
        val first = assertNotNull(LayoutEdits.placeWidget(doc, "big", specs = specs))
        // Grid 3 + widget 4 = 7: fits.
        assertEquals("home", first.pageId)
        doc = first.doc
        val second = assertNotNull(LayoutEdits.placeWidget(doc, "big", specs = specs))
        // 7 + 4 = 11: fits.
        assertEquals("home", second.pageId)
        doc = second.doc
        val third = assertNotNull(LayoutEdits.placeWidget(doc, "big", specs = specs))
        // 11 + 4 > 12: a new home screen opens.
        assertEquals(2, third.doc.pages.size)
        assertEquals("home-2", third.pageId)
        assertEquals("Home 2", third.doc.pages.last().title)
    }

    @Test
    fun `widgetRowsUsed counts headers, grids and widgets at drawn size`() {
        // w1: water at drawn 4; s1: tallest of water 4 and steps override 1 is 4.
        assertEquals(8, LayoutEdits.widgetRowsUsed(widgetDoc(), "home", lib))
        assertEquals(2, LayoutEdits.widgetRowsUsed(widgetDoc(), "two", lib))
        assertEquals(0, LayoutEdits.widgetRowsUsed(widgetDoc(), "missing", lib))
        val grid = LayoutDoc(pages = listOf(Page("home", sections = listOf(HeaderSection("h"), GridSection("g", title = "T", columns = 4, items = items("a", "b", "c", "d", "e"))))))
        // Header 4 (clock + date) + grid title 1 + ceil(5/4)*2 rows.
        assertEquals(4 + 1 + 4, LayoutEdits.widgetRowsUsed(grid, "home"))
    }

    @Test
    fun `removeWidgetRefs drops sections and strips stacks`() {        val doc = widgetDoc()
        val out = assertNotNull(LayoutEdits.removeWidgetRefs(doc, "water"))
        assertEquals(emptyList(), LayoutEdits.widgetPlacements(out, "water"))
        // The water section is gone; s1 keeps its steps ref, s2 is untouched.
        val stacks = out.pages.flatMap { it.sections }.filterIsInstance<StackSection>()
        assertEquals(listOf("s1", "s2"), stacks.map { it.id })
        assertEquals(listOf(WidgetRef("steps", rowsOverride = 1)), stacks.first().refs)
        assertNull(LayoutEdits.removeWidgetRefs(doc, "missing"))
    }

    @Test
    fun `resizeWidget sets the override on every matching ref`() {
        val out = assertNotNull(LayoutEdits.resizeWidget(widgetDoc(), "water", 3))
        val refs = out.pages.flatMap { it.sections }.flatMap {
            when (it) {
                is WidgetSection -> listOfNotNull(it.ref)
                is StackSection -> it.refs
                else -> emptyList()
            }
        }.filter { it.widgetId == "water" }
        assertEquals(2, refs.size)
        assertEquals(listOf(3, 3), refs.map { it.rowsOverride })
        assertNull(LayoutEdits.resizeWidget(widgetDoc(), "missing", 3))
    }

    private fun widgetDoc() = LayoutDoc(
        pages = listOf(
            Page(
                "home",
                sections = listOf(
                    WidgetSection("w1", ref = WidgetRef("water")),
                    StackSection("s1", refs = listOf(WidgetRef("water"), WidgetRef("steps", rowsOverride = 1))),
                ),
            ),
            Page("two", sections = listOf(StackSection("s2", refs = listOf(WidgetRef("steps"))))),
        ),
    )

    @Test
    fun `placeHtml appends a sized section with a free id`() {
        val once = assertNotNull(LayoutEdits.placeHtml(widgetDoc(), "dice"))
        val section = once.doc.pages.first().sections.last() as HtmlSection
        assertEquals("dice", section.appId)
        assertEquals(2, section.rows)
        assertEquals("home", once.pageId)
        val twice = assertNotNull(LayoutEdits.placeHtml(once.doc, "dice", rows = 3))
        // Home holds 6, so rows 3 still fits at 9.
        assertEquals("home", twice.pageId)
        assertEquals(3, (twice.doc.pages.first().sections.last() as HtmlSection).rows)
        assertEquals(twice.doc.pages.flatMap { it.sections }.map { it.id }.size, twice.doc.pages.flatMap { it.sections }.map { it.id }.toSet().size)
        assertNull(LayoutEdits.placeHtml(widgetDoc(), ""))
        assertNull(LayoutEdits.placeHtml(widgetDoc(), "dice", pageId = "missing"))
    }

    @Test
    fun `removeHtmlApp drops only matching sections`() {
        val doc = assertNotNull(LayoutEdits.placeHtml(widgetDoc(), "dice")).doc
        assertEquals(listOf(LayoutEdits.WidgetPlacement("home", "html-dice")), LayoutEdits.htmlPlacements(doc, "dice"))
        val out = assertNotNull(LayoutEdits.removeHtmlApp(doc, "dice"))
        assertEquals(emptyList(), LayoutEdits.htmlPlacements(out, "dice"))
        assertEquals(doc.pages.first().sections.size - 1, out.pages.first().sections.size)
        assertNull(LayoutEdits.removeHtmlApp(doc, "missing"))
    }

    @Test
    fun `the reported overflowing screen sends new widgets to a new page`() {
        // The Redmi screen from the bug report: clock + date, an Essentials grid, the gauge,
        // habits and price widgets from the bundled templates.
        val lib = com.samosaai.masala.core.widgets.WidgetTemplates.all().associate { it.widget.id to it.widget }
        val doc = LayoutDoc(
            pages = listOf(
                Page(
                    "home",
                    sections = listOf(
                        HeaderSection("h", showClock = true, showDate = true),
                        GridSection("g", title = "Essentials", columns = 4, items = items("a", "b", "c")),
                        WidgetSection("w1", ref = WidgetRef("battery-steps")),
                        WidgetSection("w2", ref = WidgetRef("habits")),
                        WidgetSection("w3", ref = WidgetRef("price")),
                    ),
                ),
            ),
        )
        // Sanity: that screen really does overflow a 12-row page.
        assertTrue(LayoutEdits.widgetRowsUsed(doc, "home", lib) > LayoutChecker.MAX_PAGE_ROWS)
        val placed = assertNotNull(LayoutEdits.placeWidget(doc, "water", specs = lib))
        assertEquals("home-2", placed.pageId)
        assertEquals("Home 2", placed.doc.pages.last().title)
    }
}
