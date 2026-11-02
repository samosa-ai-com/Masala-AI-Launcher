package com.samosaai.masala.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics

/** The ink the dim is made of: the glass ink, so it reads as the same material as the cards. */
private val Dim = Color(0xFF0C0C0E)

/**
 * Home seen through the typing sheet (plan `docs/plans/reply-sheet-refresh.md`): a gradient that
 * darkens towards the sheet, wallpaper included (it can't be blurred; the layout above it is), fading
 * in as the sheet opens. A tap on it closes the sheet. Hidden from TalkBack: the sheet carries Close.
 */
@Composable
fun AskBackdrop(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f) }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown.value }
            .background(Brush.verticalGradient(listOf(Dim.copy(alpha = 0.30f), Dim.copy(alpha = 0.55f))))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .clearAndSetSemantics { },
    )
}
