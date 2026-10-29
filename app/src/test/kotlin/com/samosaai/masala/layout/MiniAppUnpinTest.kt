package com.samosaai.masala.layout

import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.HeaderSection
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.Page
import org.junit.Assert.assertEquals
import org.junit.Test

/** Deleting a mini app takes its pins off home: every section, other apps' pins left alone. */
class MiniAppUnpinTest {
    private fun miniApp(id: String) = Item("miniapp-$id", id, IconRef.Symbol("widgets"), MasalaAction.OpenMiniApp(id))
    private val app = Item("app", "Clock", IconRef.Symbol("schedule"), MasalaAction.LaunchApp("com.example.clock"))

    @Test fun removesTheAppFromEverySectionAndKeepsTheRest() {
        val doc = LayoutDoc(
            pages = listOf(
                Page("p1", sections = listOf(
                    HeaderSection("h"),
                    GridSection("favorites", items = listOf(app, miniApp("snake"), miniApp("tetris"))),
                    DockSection("dock", items = listOf(miniApp("snake"))),
                )),
                Page("p2", sections = listOf(GridSection("g", items = listOf(miniApp("snake"))))),
            ),
        )
        val cleaned = LayoutRepository.withoutMiniApp(doc, "snake")
        val items = cleaned.pages.map { page -> page.sections.filterIsInstance<com.samosaai.masala.core.contract.ItemSection>().map { s -> s.items.map { it.id } } }
        assertEquals(listOf(listOf(listOf("app", "miniapp-tetris"), emptyList()), listOf(emptyList())), items)
        assertEquals(doc.pages[0].sections[0], cleaned.pages[0].sections[0])
    }

    @Test fun aLayoutWithoutTheAppIsUnchanged() {
        val doc = LayoutDoc(pages = listOf(Page("p", sections = listOf(GridSection("favorites", items = listOf(app))))))
        assertEquals(doc, LayoutRepository.withoutMiniApp(doc, "snake"))
    }
}
