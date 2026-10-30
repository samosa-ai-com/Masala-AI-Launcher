package com.samosaai.masala.ui.secondbrain

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/** The brain shell's silhouette: the hull drops inner, collinear and repeated points. */
class ConvexHullTest {
    private val square = listOf(Offset(0f, 0f), Offset(4f, 0f), Offset(4f, 4f), Offset(0f, 4f))

    @Test
    fun innerPointsAreDropped() {
        val hull = convexHull(square + Offset(2f, 2f) + Offset(1f, 3f))
        assertEquals(square.toSet(), hull.toSet())
        assertEquals(4, hull.size)
    }

    @Test
    fun collinearAndRepeatedPointsAreDropped() {
        val hull = convexHull(square + Offset(2f, 0f) + Offset(0f, 0f) + Offset(4f, 4f))
        assertEquals(square.toSet(), hull.toSet())
        assertEquals(4, hull.size)
    }

    @Test
    fun fewerThanThreePointsComeBackAsTheyAre() {
        assertEquals(listOf(Offset(1f, 1f)), convexHull(listOf(Offset(1f, 1f), Offset(1f, 1f))))
        assertEquals(emptyList<Offset>(), convexHull(emptyList()))
    }
}
