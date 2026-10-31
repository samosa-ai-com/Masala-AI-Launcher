package com.samosaai.masala.ui.character

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.samosaai.masala.ui.theme.LocalMotion
import java.util.concurrent.ConcurrentHashMap

/**
 * Sam, the golden samosa (plan UI refresh §1, "Character"), drawn from the same path data as the
 * design samples on a 200 × 200 grid: a rounded-triangle body with a darker facet and a highlight, nine
 * crust scallops, a coriander leaf, big eyes, cheeks and a small mouth, arms only when a pose needs them.
 */
object SamArt {
    val Gold = Color(0xFFEBCB80)
    val Facet = Color(0xFFDDB868)
    val Crust = Color(0xFFC99A45)
    val Highlight = Color(0xFFF6E3B4)
    val Leaf = Color(0xFF6CC08B)
    val Ink = Color(0xFF242527)
    val Cheek = Color(0xFFFF8A6B)

    const val BODY = "M88 30 Q100 10 112 30 L172 146 Q182 168 158 170 L42 170 Q18 168 28 146 Z"
    const val FACET = "M112 30 L172 146 Q182 168 158 170 L136 170 Q150 120 112 30Z"
    const val SHINE = "M84 46 Q70 72 50 110"
    const val LEAF = "M100 24 Q88 6 103 2 Q113 14 100 24Z"
    const val ARM_LEFT = "M36 132 Q22 140 20 154"
    const val ARM_WAVE = "M164 128 Q186 118 190 96"
    const val MOUTH_OPEN = "M86 127 Q100 146 114 127Z"

    /** Crust scallops along the bottom edge: x, y (radius 6). */
    val SCALLOPS = listOf(42f to 168f, 56.5f to 169f, 71f to 169.5f, 85.5f to 170f, 100f to 170f, 114.5f to 170f, 129f to 169.5f, 143.5f to 169f, 158f to 168f)

    private val cache = ConcurrentHashMap<String, Path>()
    fun path(d: String): Path = cache.getOrPut(d) { PathParser().parsePathString(d).toPath() }
}

/**
 * Sam saying hello: breathing, blinking and waving (first-run board). Reduced motion keeps him still.
 * The design's view box is (-15, -10, 230, 210), so the waving arm and the shadow fit.
 */
@Composable
fun SamHello(modifier: Modifier = Modifier, contentDescription: String = "Sam waving hello") {
    val still = LocalMotion.current.reduced
    // Same as the mark: no infinite transition while still, so reduced motion (and the Welcome
    // screen sitting open) doesn't keep polling Choreographer. States are read in the draw
    // lambda, so frames invalidate only drawing, not composition.
    val breathe: State<Float>?
    val wave: State<Float>?
    val blink: State<Float>?
    if (still) {
        breathe = null
        wave = null
        blink = null
    } else {
        val t = rememberInfiniteTransition(label = "sam")
        breathe = t.animateFloat(0f, -3f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
        wave = t.animateFloat(-14f, 18f, infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wave")
        blink = t.animateFloat(
            1f, 1f,
            infiniteRepeatable(keyframes { durationMillis = 4400; 1f at 4090; 0.08f at 4224; 1f at 4400 }),
            label = "blink",
        )
    }
    Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
        val s = minOf(size.width / 230f, size.height / 210f)
        val dx = (size.width - 230f * s) / 2f
        val dy = (size.height - 210f * s) / 2f
        translate(dx + 15f * s, dy + 10f * s) {
            scale(s, pivot = Offset.Zero) {
                drawOval(Color.Black.copy(alpha = 0.4f), Offset(48f, 179f), Size(104f, 14f))
                translate(0f, breathe?.value ?: 0f) { drawSam(wave?.value ?: -14f, blink?.value ?: 1f) }
            }
        }
    }
}

/** One frame of Sam with the waving arm at [waveDeg] and the eyes open by [eyeOpen] (0..1). */
internal fun DrawScope.drawSam(waveDeg: Float, eyeOpen: Float) {
    val crustStroke = Stroke(12f, cap = StrokeCap.Round)
    val armStroke = Stroke(8f, cap = StrokeCap.Round)
    drawPath(SamArt.path(SamArt.ARM_LEFT), SamArt.Crust, style = crustStroke)
    drawPath(SamArt.path(SamArt.ARM_LEFT), SamArt.Gold, style = armStroke)
    rotate(waveDeg, Offset(164f, 128f)) {
        drawPath(SamArt.path(SamArt.ARM_WAVE), SamArt.Crust, style = crustStroke)
        drawPath(SamArt.path(SamArt.ARM_WAVE), SamArt.Gold, style = armStroke)
    }
    drawPath(SamArt.path(SamArt.LEAF), SamArt.Leaf)
    drawPath(SamArt.path(SamArt.BODY), SamArt.Gold)
    drawPath(SamArt.path(SamArt.FACET), SamArt.Facet)
    drawPath(SamArt.path(SamArt.SHINE), SamArt.Highlight, style = Stroke(6f, cap = StrokeCap.Round))
    SamArt.SCALLOPS.forEach { (x, y) -> drawCircle(SamArt.Crust, 6f, Offset(x, y)) }
    withTransform({ scale(1f, eyeOpen, Offset(100f, 104f)) }) {
        for (x in listOf(80f, 120f)) {
            drawOval(Color.White, Offset(x - 13f, 88f), Size(26f, 32f))
            drawCircle(SamArt.Ink, 7f, Offset(x + 2f, 107f))
            drawCircle(Color.White, 2.5f, Offset(x + 5f, 103f))
        }
    }
    for (x in listOf(64f, 136f)) drawOval(SamArt.Cheek.copy(alpha = 0.6f), Offset(x - 7f, 120.5f), Size(14f, 9f))
    drawPath(SamArt.path(SamArt.MOUTH_OPEN), SamArt.Ink)
    drawPath(SamArt.path(SamArt.MOUTH_OPEN), SamArt.Ink, style = Stroke(3f))
}

/**
 * Sam in [state] (states board), at any size: full detail from 96 dp; no crust or cheeks below 48 dp;
 * at 24 dp and under a triangle with two eyes. [level] (0..1) drives the listening rings and the
 * speaking mouth. Reduced motion keeps him still (no hops, sway or rings).
 */
@Composable
fun Sam(state: CharacterState, modifier: Modifier = Modifier, level: () -> Float = { 0f }, contentDescription: String? = null) {
    val still = LocalMotion.current.reduced
    // No infinite transition while still: even constant animations poll Choreographer every
    // vsync. States are read in the draw lambda, so frames invalidate only drawing.
    val loop: State<Float>?
    val blink: State<Float>?
    if (still) {
        loop = null
        blink = null
    } else {
        val t = rememberInfiniteTransition(label = "sam")
        loop = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = androidx.compose.animation.core.LinearEasing)), label = "loop")
        blink = t.animateFloat(
            1f, 1f,
            infiniteRepeatable(keyframes { durationMillis = 4600; 1f at 4280; 0.08f at 4416; 1f at 4600 }),
            label = "blink",
        )
    }
    val face = remember(state) { SamFaces.of(state.face) }
    Canvas(modifier.then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
        val px = size.minDimension
        val dp = px / density
        val s = px / 200f
        val phase = (loop?.value ?: 0f) * 2 * Math.PI.toFloat()
        val (dy, sway) = when (state) {
            CharacterState.DONE -> -kotlin.math.abs(kotlin.math.sin(phase * 2)) * 10f to 0f
            CharacterState.THINKING, CharacterState.SEARCHING -> 0f to kotlin.math.sin(phase) * 4f
            CharacterState.LISTENING -> 0f to kotlin.math.sin(phase) * 3f
            CharacterState.SLEEPING -> kotlin.math.sin(phase) * 1.5f to 0f
            else -> kotlin.math.sin(phase) * 2.5f to 0f
        }
        translate((size.width - px) / 2f, (size.height - px) / 2f) {
            scale(s, pivot = Offset.Zero) {
                if (state == CharacterState.LISTENING && !still) {
                    val l = level().coerceIn(0f, 1f)
                    for (k in 0..1) {
                        val p = ((loop?.value ?: 0f) + k * 0.5f) % 1f
                        drawCircle(Color(0xFF9DB4FF).copy(alpha = (0.6f * (1 - p)) * (0.4f + 0.6f * l)), 70f + 40f * p + 20f * l, Offset(100f, 110f), style = Stroke(3f))
                    }
                }
                if (dp >= 48f) drawOval(Color.Black.copy(alpha = 0.35f), Offset(48f, 179f), Size(104f, 14f))
                translate(0f, if (still) 0f else dy) {
                    rotate(face.tilt + if (still) 0f else sway, Offset(100f, 170f)) {
                        drawFace(face, dp, blink?.value ?: 1f, if (state == CharacterState.SPEAKING) (0.3f + 0.7f * level().coerceIn(0f, 1f)) * (0.6f + 0.4f * kotlin.math.abs(kotlin.math.sin(phase * 6))) else null)
                    }
                }
                if ((state == CharacterState.THINKING || state == CharacterState.SEARCHING) && dp >= 48f && !still) {
                    // Spices orbiting the leaf (working board).
                    val dots = listOf(Color(0xFFFF8A6B) to 6f, Color(0xFF7FDBA8) to 5f, Color(0xFF9DB4FF) to 4f)
                    dots.forEachIndexed { i, (c, r) ->
                        val a = phase + i * 2.1f
                        drawCircle(c, r, Offset(100f + 26f * kotlin.math.cos(a), 14f + 12f * kotlin.math.sin(a)))
                    }
                }
            }
        }
    }
}

/** Body, crust, leaf and [face]; [mouthOpen] (0..1) replaces the mouth while speaking. */
private fun DrawScope.drawFace(face: SamFace, dp: Float, blink: Float, mouthOpen: Float?) {
    if (dp <= 24f) {
        drawPath(SamArt.path(SamArt.BODY), face.bodyFill)
        drawCircle(SamArt.Ink, 18f, Offset(78f, 108f))
        drawCircle(SamArt.Ink, 18f, Offset(122f, 108f))
        return
    }
    val detailed = dp >= 48f
    drawPath(SamArt.path(face.leaf), face.leafFill)
    drawPath(SamArt.path(SamArt.BODY), face.bodyFill)
    if (detailed) {
        drawPath(SamArt.path(SamArt.FACET), face.shadeFill)
        drawPath(SamArt.path(SamArt.SHINE), SamArt.Highlight.copy(alpha = face.cheek.coerceIn(0f, 1f)), style = Stroke(6f, cap = StrokeCap.Round))
        SamArt.SCALLOPS.forEach { (x, y) -> drawCircle(SamArt.Crust, 6f, Offset(x, y)) }
    }
    withTransform({ scale(1f, blink, Offset(100f, 104f)) }) {
        if (face.whites.isNotBlank()) {
            drawPath(SamArt.path(face.whites), Color.White)
            drawPath(SamArt.path(face.whites), SamArt.Ink.copy(alpha = 0.25f), style = Stroke(1.5f))
        }
        if (face.pupils.isNotBlank()) drawPath(SamArt.path(face.pupils), SamArt.Ink)
        if (face.shine.isNotBlank()) drawPath(SamArt.path(face.shine), Color.White)
    }
    val line = Stroke(if (detailed) 4.5f else 6f, cap = StrokeCap.Round)
    if (face.lids.isNotBlank()) drawPath(SamArt.path(face.lids), SamArt.Ink, style = line)
    if (face.brows.isNotBlank()) drawPath(SamArt.path(face.brows), SamArt.Ink, style = Stroke(4f, cap = StrokeCap.Round))
    if (detailed) drawPath(SamArt.path(SamFaces.CHEEKS), SamArt.Cheek.copy(alpha = face.cheek))
    if (mouthOpen != null) {
        drawOval(SamArt.Ink, Offset(92f, 128f), Size(16f, 4f + 12f * mouthOpen))
    } else {
        val mouth = SamArt.path(face.mouth)
        if (face.mouthFilled) drawPath(mouth, SamArt.Ink)
        drawPath(mouth, SamArt.Ink, style = Stroke(4f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
    if (detailed && face.extra.isNotBlank()) {
        face.extraFill?.let { drawPath(SamArt.path(face.extra), it) }
        face.extraStroke?.let { drawPath(SamArt.path(face.extra), it, style = Stroke(3.5f, cap = StrokeCap.Round)) }
    }
}
