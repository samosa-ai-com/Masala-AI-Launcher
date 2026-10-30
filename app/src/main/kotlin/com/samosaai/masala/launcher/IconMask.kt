package com.samosaai.masala.launcher

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.samosaai.masala.data.IconShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * The one mask every icon is drawn in (plan UI refresh §1, "Icons"): a squircle by default (a
 * superellipse, so the corners curve continuously), a circle, or a rounded square. The same path clips
 * app icons in [IconCache] and shapes symbol tiles in Compose, so the two always match.
 */
object IconMask {
    /** Superellipse exponent: 2 is a circle; higher is squarer. Close to the brand's 30/96 corner. */
    private const val SQUIRCLE_N = 4.6

    /** Corner radius of the rounded square, as a share of the side (brand board: 18 on 96). */
    private const val SQUARE_CORNER = 0.19f

    /** The squircle on a 1×1 grid, built once; per-size copies scale it instead of redoing the trig. */
    private val unitSquircle: Path by lazy { squircle(1f, 1f) }

    fun path(shape: IconShape, width: Float, height: Float): Path = when (shape) {
        IconShape.CIRCLE -> Path().apply { addOval(RectF(0f, 0f, width, height), Path.Direction.CW) }
        IconShape.SQUARE, IconShape.AS_SHIPPED -> Path().apply {
            val r = minOf(width, height) * SQUARE_CORNER
            addRoundRect(RectF(0f, 0f, width, height), r, r, Path.Direction.CW)
        }
        // A fresh copy per call: the same Path is drawn on the IO thread (IconCache) and the UI
        // thread (tiles), and Path is not thread-safe.
        IconShape.SQUIRCLE -> Path(unitSquircle).apply { transform(Matrix().apply { setScale(width, height) }) }
    }

    private fun squircle(width: Float, height: Float): Path = Path().apply {
        val cx = width / 2f
        val cy = height / 2f
        val steps = 96
        for (i in 0..steps) {
            val t = 2 * Math.PI * i / steps
            val c = cos(t)
            val s = sin(t)
            val x = cx + cx * sign(c) * abs(c).pow(2 / SQUIRCLE_N)
            val y = cy + cy * sign(s) * abs(s).pow(2 / SQUIRCLE_N)
            if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
        }
        close()
    }

    /** The mask as a Compose [Shape], for tiles drawn next to app icons. */
    fun shape(shape: IconShape): Shape = object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
            Outline.Generic(path(shape, size.width, size.height).asComposePath())
    }
}
