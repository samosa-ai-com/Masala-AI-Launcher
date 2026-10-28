package com.samosaai.masala.core

import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.layout.LayoutHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutHistoryTest {
    private fun doc(name: String) = LayoutDoc(pages = listOf(Page(name, sections = emptyList())))
    private fun LayoutHistory.name() = current?.layout?.pages?.single()?.id

    @Test
    fun `undo and redo walk the versions`() {
        val h = LayoutHistory()
        assertFalse(h.canUndo); assertNull(h.current)
        h.push("one", doc("one"), 1); h.push("two", doc("two"), 2); h.push("three", doc("three"), 3)
        assertEquals("three", h.name())
        assertEquals("two", h.undo()?.layout?.pages?.single()?.id)
        assertEquals("one", h.undo()?.layout?.pages?.single()?.id)
        assertNull(h.undo())
        assertTrue(h.canRedo)
        assertEquals("two", h.redo()?.layout?.pages?.single()?.id)
    }

    @Test
    fun `a change after an undo drops the undone versions`() {
        val h = LayoutHistory()
        h.push("one", doc("one"), 1); h.push("two", doc("two"), 2)
        h.undo()
        val change = h.push("branch", doc("branch"), 3)
        assertEquals(1, change.removedIds.size)
        assertEquals(listOf("one", "branch"), h.versions.map { it.label })
        assertFalse(h.canRedo)
    }

    @Test
    fun `only the newest fifty versions are kept`() {
        val h = LayoutHistory()
        var removed = 0
        repeat(55) { removed += h.push("v$it", doc("v$it"), it.toLong()).removedIds.size }
        assertEquals(50, h.versions.size)
        assertEquals(5, removed)
        assertEquals("v5", h.versions.first().label)
        assertEquals("v54", h.current?.label)
    }

    @Test
    fun `ids keep growing so a saved version is never confused with a new one`() {
        val h = LayoutHistory()
        val a = h.push("a", doc("a"), 1).added!!
        h.undo(); h.push("b", doc("b"), 2)
        val c = h.push("c", doc("c"), 3).added!!
        assertTrue(c.id > a.id)
        assertEquals(c.id, h.current?.id)
    }

    @Test
    fun `restoring from saved versions puts the cursor on the saved current one`() {
        val saved = listOf(
            LayoutHistory.Version(3, "c", doc("c"), 3), LayoutHistory.Version(1, "a", doc("a"), 1), LayoutHistory.Version(2, "b", doc("b"), 2),
        )
        val h = LayoutHistory(saved, current = 2)
        assertEquals("b", h.current?.label)
        assertTrue(h.canUndo && h.canRedo)
        assertEquals("a", h.select(1)?.label)
        assertEquals(4, h.push("d", doc("d"), 4).added?.id)
    }

    @Test
    fun `rename keeps order and current`() {
        val h = LayoutHistory()
        h.push("one", doc("one"), 1); h.push("two", doc("two"), 2)
        val two = h.versions.last()
        assertEquals("Two", h.rename(two.id, "Two")?.label)
        assertEquals(listOf("one", "Two"), h.versions.map { it.label })
        assertEquals("Two", h.current?.label)
        assertNull(h.rename(999, "nope"))
    }

    @Test
    fun `delete moves current to the predecessor`() {
        val h = LayoutHistory()
        h.push("one", doc("one"), 1); h.push("two", doc("two"), 2); h.push("three", doc("three"), 3)
        h.select(h.versions[1].id)
        val now = h.delete(h.current!!.id)
        assertEquals("one", now?.label)
        assertEquals(listOf("one", "three"), h.versions.map { it.label })
    }

    @Test
    fun `delete refuses the last row and unknown ids`() {
        val h = LayoutHistory()
        h.push("only", doc("only"), 1)
        assertNull(h.delete(h.current!!.id))
        assertEquals(1, h.versions.size)
        assertNull(h.delete(999))
    }
}
