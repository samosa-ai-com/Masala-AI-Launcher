package com.samosaai.masala.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DropTargetsTest {
    // A 4-column grid of 100 px cells, two rows: g0..g3 on top, g4..g7 below.
    private val cells = (0 until 8).map { i ->
        val x = (i % 4) * 100f
        val y = (i / 4) * 100f
        "g$i" to Box2(x, y, x + 100, y + 100)
    }
    private val sections = mapOf("grid" to Box2(0f, 0f, 400f, 200f))
    private val items = mapOf("grid" to cells)

    private fun resolve(x: Float, y: Float, dragged: String = "other") = DropTargets.resolve(x, y, sections, items, dragged)

    @Test
    fun `the centre of an item is onto it`() {
        assertEquals(DropTarget.Onto("g5"), resolve(150f, 150f))
    }

    @Test
    fun `the edge of an item is a slot`() {
        assertEquals(DropTarget.Slot("grid", 1, "g1"), resolve(105f, 50f))
    }

    @Test
    fun `slot at the start`() {
        assertEquals(DropTarget.Slot("grid", 0, "g0"), resolve(5f, 50f))
    }

    @Test
    fun `slot in the middle of the second row`() {
        assertEquals(DropTarget.Slot("grid", 6, "g6"), resolve(195f, 150f))
    }

    @Test
    fun `slot at the end`() {
        assertEquals(DropTarget.Slot("grid", 8), resolve(395f, 150f))
    }

    @Test
    fun `the dragged item is not counted`() {
        assertEquals(DropTarget.Slot("grid", 7, null), resolve(395f, 150f, dragged = "g0"))
    }

    @Test
    fun `outside every section is null`() {
        assertNull(resolve(500f, 500f))
    }

    @Test
    fun `the dragged item itself is never onto`() {
        assertEquals(DropTarget.Slot("grid", 0, "g1"), resolve(50f, 50f, dragged = "g0"))
    }
}
