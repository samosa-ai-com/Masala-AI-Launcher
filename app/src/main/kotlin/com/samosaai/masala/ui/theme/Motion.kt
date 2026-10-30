package com.samosaai.masala.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.samosaai.masala.data.MotionLevel

/**
 * Motion tokens (design system board, "Motion & haptics"): press scale, drawer spring, sheets rising in
 * 280 ms, mode colours morphing over 600 ms, AI layout sections 40–120 ms apart. [reduced] turns
 * movement into crossfades: the user's setting, or the phone's animator scale at 0.
 */
@Immutable
data class Motion(val reduced: Boolean) {
    /** Springs where movement is allowed; a short crossfade-length tween when reduced. */
    fun <T> spring(stiffness: Float = Spring.StiffnessMediumLow, damping: Float = Spring.DampingRatioNoBouncy): AnimationSpec<T> =
        if (reduced) tween(CROSSFADE_MS) else spring(dampingRatio = damping, stiffness = stiffness)

    companion object {
        const val PRESS_SCALE = 0.94f
        const val PRESS_MS = 90
        const val CROSSFADE_MS = 150
        const val SHEET_MS = 280
        const val MODE_MORPH_MS = 600
        const val SECTION_STAGGER_MIN_MS = 40
        const val SECTION_STAGGER_MAX_MS = 120

        /** How far home shrinks while the drawer is open. */
        const val HOME_UNDER_DRAWER_SCALE = 0.94f
    }
}

val LocalMotion = staticCompositionLocalOf { Motion(reduced = false) }

@Composable
internal fun rememberMotion(level: MotionLevel): Motion {
    val context = LocalContext.current
    val systemOff = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) == 0f
    }
    return remember(level, systemOff) { Motion(reduced = level == MotionLevel.REDUCED || systemOff) }
}

/** Scales to 0.94 while pressed and springs back (none with reduced motion). */
@Composable
fun Modifier.pressScale(interaction: InteractionSource): Modifier {
    val motion = LocalMotion.current
    if (motion.reduced) return this
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) Motion.PRESS_SCALE else 1f,
        if (pressed) tween(Motion.PRESS_MS) else spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
