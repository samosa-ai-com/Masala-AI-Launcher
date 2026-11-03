package com.samosaai.masala.tools.sam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Shared mask painting for the still overlay ([com.samosaai.masala.tools.SamSegmentTool])
 * and the annotated video clip ([com.samosaai.masala.tools.SegmentVideoTool]):
 * one hue per object, a translucent fill plus an edge outline.
 *
 * Mask rasters are box-local (their H×W tracks the box size), so callers map
 * the record's box onto the destination frame first and pass that rect as
 * [dst]; the raster is stretched onto it.
 */
internal object MaskDraw {
    /** Distinct hue per object; the still and video overlays use the same rotation. */
    fun hueFor(index: Int): Float = (index * 137 % 360).toFloat()

    fun fillFor(index: Int, alpha: Int = 110): Int =
        Color.HSVToColor(alpha, floatArrayOf(hueFor(index), 0.85f, 1f))

    fun edgeFor(index: Int): Int =
        Color.HSVToColor(255, floatArrayOf(hueFor(index), 0.9f, 1f))

    /** Stretches the binary raster onto [dst] as one unfiltered bitmap draw. */
    fun fillOn(canvas: Canvas, raster: ByteArray, w: Int, h: Int, dst: RectF, fill: Int) {
        val pixels = IntArray(w * h) { i -> if (raster[i] != 0.toByte()) fill else 0 }
        val mask = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(mask, null, dst, Paint().apply { isFilterBitmap = false })
        if (!mask.isRecycled()) mask.recycle()
    }

    /**
     * Thin outline: strokes the scaled cell of every set pixel touching an
     * unset one, with a fixed [strokeWidthPx] in destination pixels.
     */
    fun edgeOn(
        canvas: Canvas,
        raster: ByteArray,
        w: Int,
        h: Int,
        dst: RectF,
        edge: Int,
        strokeWidthPx: Float,
    ) {
        val cellW = dst.width() / w
        val cellH = dst.height() / h
        val paint = Paint().apply {
            color = edge
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
        }
        // Keep tiny cells legible: never thinner than half a cell.
        paint.strokeWidth = maxOf(strokeWidthPx, minOf(cellW, cellH) / 2f)
        fun isSet(x: Int, y: Int): Boolean =
            x in 0 until w && y in 0 until h && raster[y * w + x] != 0.toByte()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!isSet(x, y)) continue
                if (isSet(x - 1, y) && isSet(x + 1, y) && isSet(x, y - 1) && isSet(x, y + 1)) continue
                canvas.drawRect(
                    dst.left + x * cellW,
                    dst.top + y * cellH,
                    dst.left + (x + 1) * cellW,
                    dst.top + (y + 1) * cellH,
                    paint,
                )
            }
        }
    }
}
