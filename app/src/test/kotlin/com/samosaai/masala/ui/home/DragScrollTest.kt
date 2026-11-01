package com.samosaai.masala.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DragScrollTest {
    // A 1000 px list with a 100 px band at each end, at most 10 px a frame.
    private fun delta(y: Float) = DragScroll.delta(y, top = 0f, bottom = 1000f, band = 100f, maxPerFrame = 10f)

    @Test
    fun `the deepest point of each band scrolls at full speed towards that end`() {
        assertEquals(-10f, delta(0f))
        assertEquals(10f, delta(1000f))
    }

    @Test
    fun `the speed ramps across the band`() {
        assertEquals(-5f, delta(50f))
        assertEquals(5f, delta(950f))
    }

    @Test
    fun `between the bands nothing moves`() {
        assertEquals(0f, delta(500f))
        assertEquals(0f, delta(101f))
        assertEquals(0f, delta(899f))
    }

    @Test
    fun `outside the list nothing moves`() {
        assertEquals(0f, delta(-20f))
        assertEquals(0f, delta(1200f))
        assertEquals(0f, DragScroll.delta(10f, 200f, 300f, 40f, 10f))
    }

    @Test
    fun `a band taller than half the list still ramps`() {
        val d = DragScroll.delta(10f, top = 0f, bottom = 100f, band = 200f, maxPerFrame = 10f)
        assertEquals(-8f, d)
        assertTrue(d > -10f)
    }
}
