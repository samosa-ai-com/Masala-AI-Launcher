package com.samosaai.masala.tools.sam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CutoutTest {
    // 4x4 mask with a centred 2x2 block, stretched over an 8x8 source
    // (each cell = 2x2 source pixels), opaque red source.
    private val mask = ByteArray(16) { i ->
        val x = i % 4
        val y = i / 4
        if (x in 1..2 && y in 1..2) 1 else 0
    }
    private val src = IntArray(64) { 0xFFFF0000.toInt() }

    @Test
    fun `interior pixels are fully opaque with rgb kept`() {
        val out = Cutout.applyCutout(src, 8, 8, mask, 4, 4, 0f, 0f, 8f, 8f)
        // Source px (3,3)..(4,4) sit deep inside the block's cells.
        for (y in 3..4) {
            for (x in 3..4) {
                val p = out[y * 8 + x]
                assertEquals(0xFF, (p ushr 24) and 0xFF, "alpha at ($x,$y)")
                assertEquals(0xFFFF0000.toInt(), p, "pixel at ($x,$y)")
            }
        }
    }

    @Test
    fun `exterior pixels are fully transparent`() {
        val out = Cutout.applyCutout(src, 8, 8, mask, 4, 4, 0f, 0f, 8f, 8f)
        assertEquals(0, out[0])
        assertEquals(0, out[7])
        assertEquals(0, out[7 * 8])
        assertEquals(0, out[63])
        assertEquals(0, out[1 * 8 + 1])
    }

    @Test
    fun `mask edge feathers over about one source pixel`() {
        val out = Cutout.applyCutout(src, 8, 8, mask, 4, 4, 0f, 0f, 8f, 8f)
        // Row 2 is the first row of the block's top cells (cell y=1 spans
        // source rows 2..3); its pixels straddle the edge, so at least one
        // must be partially transparent, and row 1 above must be clear.
        val row2 = (0 until 8).map { (out[2 * 8 + it] ushr 24) and 0xFF }
        assertTrue(row2.any { it in 1..254 }, "row2 alphas=$row2")
        val row1 = (0 until 8).map { (out[1 * 8 + it] ushr 24) and 0xFF }
        assertTrue(row1.all { it == 0 }, "row1 alphas=$row1")
        // Edge pixels that are kept still carry the source colour.
        for (x in 0 until 8) {
            val p = out[2 * 8 + x]
            if (((p ushr 24) and 0xFF) > 0) assertEquals(0x00FF0000, p and 0x00FFFFFF)
        }
    }

    @Test
    fun `bilinear sampler reads corners and clamps outside to zero`() {
        // Single set cell at (1,1) in a 3x3 mask.
        val m = ByteArray(9) { if (it == 4) 1 else 0 }
        assertEquals(1f, Cutout.sampleBilinear(m, 3, 3, 1f, 1f))
        assertEquals(0f, Cutout.sampleBilinear(m, 3, 3, 0f, 0f))
        assertEquals(0f, Cutout.sampleBilinear(m, 3, 3, -2f, 1f))
        assertEquals(0.25f, Cutout.sampleBilinear(m, 3, 3, 0.5f, 0.5f))
    }

    @Test
    fun cropToContentTrimsToTheObjectWithPadding() {
        // 10x6 image, one opaque 2x2 block at x=6..7, y=3..4.
        val w = 10; val h = 6
        val px = IntArray(w * h)
        for (y in 3..4) for (x in 6..7) px[y * w + x] = (0xFF shl 24) or 0x112233
        val c = Cutout.cropToContent(px, w, h, pad = 1)!!
        assertEquals(4, c.width)   // 5..8
        assertEquals(4, c.height)  // 2..5 (clamped at the bottom edge)
        assertEquals((0xFF shl 24) or 0x112233, c.pixels[1 * c.width + 1])
    }

    @Test
    fun cropToContentOfAnEmptyCutoutIsNull() {
        assertEquals(null, Cutout.cropToContent(IntArray(12), 4, 3))
    }
}
