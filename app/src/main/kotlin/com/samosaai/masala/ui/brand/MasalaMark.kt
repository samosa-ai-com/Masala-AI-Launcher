package com.samosaai.masala.ui.brand

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.samosaai.masala.ui.theme.LocalMotion
import com.samosaai.masala.ui.theme.MasalaColors
import com.samosaai.masala.ui.theme.SpaceGrotesk
import java.util.concurrent.ConcurrentHashMap

/**
 * The Masala logo, a mortar and pestle in gold line art (brand board; the canvas trace for now,
 * decision 12 — the original vector replaces these paths later). Drawn on a 512 grid. Below 48 px the
 * bowl's highlight goes; at 32 px and under, the knob too, and the strokes get heavier.
 */
object MasalaMark {
    const val GRID = 512f

    /** The pestle: its shaft lines, then the knob (a rounded rect turned 40° about 339,156). */
    const val PESTLE = "M255 222 L306 170 M322 222 L345 182"
    const val KNOB = "M330 143 H348 A13 13 0 0 1 348 169 H330 A13 13 0 0 1 330 143 Z"
    const val RIM = "M146 228 A109 16 0 1 0 364 228 A109 16 0 1 0 146 228 Z"
    const val BOWL = "M146 230 C146 300 195 346 255 346 C315 346 364 300 364 230 M323 266 C321 296 297 320 260 322 M208 347 L196 369 L316 369 L304 347"
    const val BOWL_48 = "M146 230 C146 300 195 346 255 346 C315 346 364 300 364 230 M208 347 L196 369 L316 369 L304 347"
    const val PESTLE_32 = "M260 216 L330 160"
    const val BOWL_32 = "M146 230 C146 300 195 346 255 346 C315 346 364 300 364 230 M208 350 L316 350"

    /** The solid-bowl small mark (status bar, notifications). */
    const val SOLID_BOWL = "M146 228 H364 C364 300 315 346 255 346 C195 346 146 300 146 228Z"

    /** Pivot of the grinding motion. */
    val GRIND_PIVOT = Offset(290f, 236f)
}

private val parsed = ConcurrentHashMap<String, Path>()
private fun markPath(d: String): Path = parsed.getOrPut(d) { PathParser().parsePathString(d).toPath() }

/** The logo as an [ImageVector], gold strokes on nothing; [stroke] is in grid units. */
fun masalaMarkVector(color: Color = MasalaColors.Gold, stroke: Float = 16f): ImageVector =
    ImageVector.Builder("MasalaMark", 512.dp, 512.dp, MasalaMark.GRID, MasalaMark.GRID).apply {
        fun line(d: String) = addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            stroke = SolidColor(color),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        line(MasalaMark.PESTLE)
        group(rotate = 40f, pivotX = 339f, pivotY = 156f) { line(MasalaMark.KNOB) }
        line(MasalaMark.RIM)
        line(MasalaMark.BOWL)
    }.build()

/**
 * The mark drawn at [size]. With [grinding] the pestle works in the bowl and spices rise: the busy
 * sign when Sam is hidden. Reduced motion keeps it still.
 */
@Composable
fun MasalaMarkIcon(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = MasalaColors.Gold,
    grinding: Boolean = false,
    contentDescription: String? = null,
) {
    val reduced = LocalMotion.current.reduced
    val animate = grinding && !reduced
    // No infinite transition while still: even a 0-to-0 animation keeps polling Choreographer
    // every vsync, which costs battery on a home-screen launcher. The states are read inside
    // the draw lambda, so frames invalidate only the draw layer, not composition.
    val angle: State<Float>?
    val puff: State<Float>?
    if (animate) {
        val transition = rememberInfiniteTransition(label = "grind")
        angle = transition.animateFloat(-8f, 7f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "pestle")
        puff = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "puff")
    } else {
        angle = null
        puff = null
    }
    Canvas(
        modifier.size(size).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        val px = this.size.minDimension
        scale(px / MasalaMark.GRID, pivot = Offset.Zero) {
            if (animate) drawPuffs(puff?.value ?: 0f)
            drawMark(color, px, angle?.value ?: 0f)
        }
    }
}

private fun DrawScope.drawMark(color: Color, px: Float, pestleAngle: Float) {
    val width = when {
        px <= 32f * density -> 30f
        px <= 48f * density -> 22f
        else -> 16f
    }
    val stroke = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (px <= 32f * density) {
        drawPath(markPath(MasalaMark.PESTLE_32), color, style = stroke)
        drawPath(markPath(MasalaMark.RIM), color, style = stroke)
        drawPath(markPath(MasalaMark.BOWL_32), color, style = stroke)
        return
    }
    rotate(pestleAngle, MasalaMark.GRIND_PIVOT) {
        drawPath(markPath(MasalaMark.PESTLE), color, style = stroke)
        withTransform({ rotate(40f, Offset(339f, 156f)) }) { drawPath(markPath(MasalaMark.KNOB), color, style = stroke) }
    }
    drawPath(markPath(MasalaMark.RIM), color, style = stroke)
    drawPath(markPath(if (px <= 48f * density) MasalaMark.BOWL_48 else MasalaMark.BOWL), color, style = stroke)
}

/** Three spice puffs rising out of the bowl, offset in time (brand board, "Working"). */
private fun DrawScope.drawPuffs(t: Float) {
    val puffs = listOf(
        Triple(Offset(225f, 205f), 9f, MasalaColors.Chili),
        Triple(Offset(262f, 200f), 7f, MasalaColors.Cardamom),
        Triple(Offset(240f, 210f), 6f, MasalaColors.Gold),
    )
    puffs.forEachIndexed { i, (at, r, c) ->
        val p = (t + i * 0.31f) % 1f
        val alpha = if (p < 0.2f) p / 0.2f else 1f - (p - 0.2f) / 0.8f
        drawCircle(c.copy(alpha = alpha.coerceIn(0f, 1f)), r, at.copy(y = at.y - 70f * p))
    }
}

/** Mark and "masala" in Space Grotesk, lower case (decision 12). Gold on dark, charcoal word on paper. */
@Composable
fun MasalaWordmark(modifier: Modifier = Modifier, markSize: Dp = 40.dp, onPaper: Boolean = false, grinding: Boolean = false) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Masala" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MasalaMarkIcon(size = markSize, grinding = grinding)
        Text(
            "masala",
            style = TextStyle(
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = (markSize.value * 0.65f).sp,
                letterSpacing = (-0.023f).em,
                color = if (onPaper) MasalaColors.Charcoal else MasalaColors.Gold,
            ),
        )
    }
}
