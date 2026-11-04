package com.samosaai.masala.tools.sam

/**
 * Pure-JVM cutout rasteriser (no Android imports, plain unit-testable):
 * keeps source pixels where a box-local binary mask covers them, transparent
 * elsewhere, with a ~1 source-pixel feather on the mask edge.
 *
 * The mask is box-local (its W×H tracks the detection box); [left]/[top]/
 * [right]/[bottom] are that box already mapped into source-pixel space.
 */
object Cutout {
    /**
     * Bilinear sample of the binary [mask] at cell coordinates ([u],[v]);
     * cell centres sit at integer+0.5, out-of-bounds reads as 0.
     */
    fun sampleBilinear(mask: ByteArray, w: Int, h: Int, u: Float, v: Float): Float {
        fun at(x: Int, y: Int): Float =
            if (x in 0 until w && y in 0 until h && mask[y * w + x] != 0.toByte()) 1f else 0f
        val x0 = kotlin.math.floor(u).toInt()
        val y0 = kotlin.math.floor(v).toInt()
        val fx = u - x0
        val fy = v - y0
        return at(x0, y0) * (1 - fx) * (1 - fy) +
            at(x0 + 1, y0) * fx * (1 - fy) +
            at(x0, y0 + 1) * (1 - fx) * fy +
            at(x0 + 1, y0 + 1) * fx * fy
    }

    /**
     * Returns ARGB pixels: source RGB kept with alpha from mask coverage,
     * 0 where uncovered. The bilinear ramp is one mask cell wide, so it is
     * remapped to [featherPx] source pixels around the 0.5 coverage point.
     */
    fun applyCutout(
        src: IntArray,
        srcW: Int,
        srcH: Int,
        mask: ByteArray,
        maskW: Int,
        maskH: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        featherPx: Float = 1f,
    ): IntArray {
        require(src.size == srcW * srcH) { "source pixel count mismatch" }
        require(mask.size == maskW * maskH) { "mask pixel count mismatch" }
        val out = IntArray(srcW * srcH)
        if (right <= left || bottom <= top) return out
        val cellW = (right - left) / maskW
        val cellH = (bottom - top) / maskH
        // Source pixels per mask cell; the coverage ramp spans one cell, so
        // scaling by it compresses the ramp to featherPx source pixels.
        val ramp = minOf(cellW, cellH).coerceAtLeast(1e-6f) / featherPx.coerceAtLeast(1e-6f)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val px = x + 0.5f
                val py = y + 0.5f
                if (px < left || px >= right || py < top || py >= bottom) continue
                val coverage = sampleBilinear(
                    mask, maskW, maskH,
                    (px - left) / cellW - 0.5f,
                    (py - top) / cellH - 0.5f,
                )
                val alpha = ((coverage - 0.5f) * ramp + 0.5f).coerceIn(0f, 1f)
                if (alpha <= 0f) continue
                val rgb = src[y * srcW + x] and 0x00FFFFFF
                out[y * srcW + x] = ((alpha * 255 + 0.5f).toInt() shl 24) or rgb
            }
        }
        return out
    }

    /** Pixels, width and height of a cut-out trimmed to its visible pixels. */
    class Cropped(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * Trims [argb] to the bounding box of its non-transparent pixels plus [pad] (clamped to the image),
     * so a cut-out shows the object rather than the object in a corner of an empty photo-sized frame.
     * Null when nothing is visible.
     */
    fun cropToContent(argb: IntArray, w: Int, h: Int, pad: Int = 8): Cropped? {
        require(argb.size == w * h) { "pixel count mismatch" }
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((argb[y * w + x] ushr 24) and 0xFF != 0) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        val l = (minX - pad).coerceAtLeast(0); val t = (minY - pad).coerceAtLeast(0)
        val r = (maxX + pad).coerceAtMost(w - 1); val b = (maxY + pad).coerceAtMost(h - 1)
        val cw = r - l + 1; val ch = b - t + 1
        val out = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(argb, (t + y) * w + l, out, y * cw, cw)
        return Cropped(out, cw, ch)
    }
}
