package com.samosaai.masala.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Section drag targets: grids count, single widgets drag, drops never land after the dock. */
class HomeDragSectionTest {
    private fun box(top: Float, bottom: Float = top + 100f) = Box2(0f, top, 720f, bottom)

    private fun drag(): HomeDragState = HomeDragState().also {
        // Page "home": header-less column of a grid, two widgets and the dock.
        it.sectionBoxes["grid"] = box(0f)
        it.sectionBoxes["w1"] = box(200f)
        it.sectionBoxes["w2"] = box(400f)
        it.sectionBoxes["dock"] = box(1400f)
        it.sectionPages["grid"] = "home"
        it.sectionPages["w1"] = "home"
        it.sectionPages["w2"] = "home"
        it.sectionPages["dock"] = "home"
    }

    @Test fun `single widget drags against grids`() {
        val d = HomeDragState().also {
            it.sectionBoxes["grid"] = box(0f)
            it.sectionBoxes["w1"] = box(300f)
            it.sectionPages["grid"] = "home"
            it.sectionPages["w1"] = "home"
        }
        d.startSection("w1", "home", androidx.compose.ui.geometry.Offset(10f, 350f))
        d.moveTo(androidx.compose.ui.geometry.Offset(10f, 50f))
        assertEquals(SectionDrop("home", "grid"), d.sectionTarget())
    }

    @Test fun `drop between widgets names the next sibling`() {
        val d = drag()
        d.startSection("w1", "home", androidx.compose.ui.geometry.Offset(10f, 250f))
        d.moveTo(androidx.compose.ui.geometry.Offset(10f, 350f))
        // Below w1's center (250) but above w2's (450): lands before w2.
        assertEquals(SectionDrop("home", "w2"), d.sectionTarget())
    }

    @Test fun `drop below everything targets the dock, landing before it`() {
        val d = drag()
        d.startSection("w2", "home", androidx.compose.ui.geometry.Offset(10f, 450f))
        d.moveTo(androidx.compose.ui.geometry.Offset(10f, 1300f))
        // Below w2 but above the dock: before the dock (moveSection inserts there, never after).
        assertEquals(SectionDrop("home", "dock"), d.sectionTarget())
    }

    @Test fun `unregistered pages never match`() {
        val d = drag()
        d.startSection("w1", "other", androidx.compose.ui.geometry.Offset(10f, 250f))
        d.moveTo(androidx.compose.ui.geometry.Offset(10f, 250f))
        assertNull(d.sectionTarget())
    }
}
