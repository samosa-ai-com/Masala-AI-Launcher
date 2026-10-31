package com.samosaai.masala.ui.character

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.samosaai.masala.data.Character
import com.samosaai.masala.ui.brand.MasalaMarkIcon
import com.samosaai.masala.ui.theme.LocalMotion
import com.samosaai.masala.ui.theme.MasalaColors

/** The minimal character (Appearance → Character → Orb): a soft glowing ball coloured by [state]. */
@Composable
fun Orb(state: CharacterState, modifier: Modifier = Modifier, level: () -> Float = { 0f }) {
    val color by animateColorAsState(
        when (state) {
            CharacterState.LISTENING, CharacterState.SPEAKING -> MasalaColors.Indigo
            CharacterState.DONE -> MasalaColors.Cardamom
            CharacterState.SORRY -> MasalaColors.Chili
            CharacterState.SLEEPING -> Color(0xFF5E8F73)
            else -> MasalaColors.Gold
        },
        label = "orb",
    )
    val still = LocalMotion.current.reduced
    val pulse by rememberInfiniteTransition(label = "orb").animateFloat(0f, if (still) 0f else 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse")
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val busy = state == CharacterState.THINKING || state == CharacterState.SEARCHING
        val grow = 0.82f + 0.08f * kotlin.math.sin(pulse * 2 * Math.PI.toFloat()) * (if (busy) 1f else 0.4f) + 0.1f * level().coerceIn(0f, 1f)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), c, r), r, c)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), color), c - Offset(r * 0.2f, r * 0.2f), r * grow), r * 0.62f * grow, c)
    }
}

/**
 * Whoever shows Masala's state, by Appearance → Character (decision 5): Sam, the orb, or with Hidden the
 * logo's pestle grinding while Masala works. One on screen at a time is the caller's job.
 */
@Composable
fun MasalaCharacter(character: Character, state: CharacterState, modifier: Modifier = Modifier, level: () -> Float = { 0f }, description: String? = null) {
    val m = if (description != null) modifier.semantics { contentDescription = description } else modifier
    when (character) {
        Character.SAM -> Sam(state, m, level)
        Character.ORB -> Orb(state, m, level)
        Character.HIDDEN -> MasalaMarkIcon(m, grinding = state == CharacterState.THINKING || state == CharacterState.SEARCHING || state == CharacterState.LISTENING)
    }
}
