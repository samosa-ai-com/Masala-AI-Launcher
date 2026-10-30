package com.samosaai.masala.ui.theme

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

val LocalHapticsEnabled = staticCompositionLocalOf { true }

/**
 * The four haptics (design system board): tick for the A–Z scrub and toggles, confirm for send and
 * apply, reject for errors, heavy for a long press. Nothing when the haptics setting is off; the
 * phone's own touch-feedback setting still applies through [View.performHapticFeedback].
 */
@Stable
class Haptics internal constructor(private val view: View, private val enabled: Boolean) {
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
    fun reject() = perform(HapticFeedbackConstants.REJECT)
    fun heavy() = perform(HapticFeedbackConstants.LONG_PRESS)

    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { Haptics(view, enabled) }
}
