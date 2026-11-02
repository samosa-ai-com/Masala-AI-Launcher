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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.ui.components.wallpaperGlass
import com.samosaai.masala.ui.components.wallpaperText
import com.samosaai.masala.ui.theme.OnWallpaper

/**
 * Above the typing sheet: Sam with a greeting, and a row of suggestion chips. A chip fills the field
 * with its prompt (never sends): [chips] pairs a label with the text it puts in the field.
 */
@Composable
fun AskIntro(
    character: com.samosaai.masala.data.Character?,
    chips: List<Pair<String, String>>,
    onChip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(horizontal = 24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            character?.let {
                com.samosaai.masala.ui.character.MasalaCharacter(it, com.samosaai.masala.ui.character.CharacterState.HELLO, Modifier.size(64.dp))
            }
            Text(
                stringResource(R.string.ask_greeting),
                style = wallpaperText(MaterialTheme.typography.headlineSmall),
                modifier = Modifier.semantics { heading() },
            )
        }
        if (chips.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { (label, prompt) ->
                    val shape = RoundedCornerShape(50)
                    Box(
                        Modifier.minimumInteractiveComponentSize().height(36.dp).wallpaperGlass(shape)
                            .clickable(role = Role.Button) { onChip(prompt) }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = OnWallpaper, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                    }
                }
            }
        }
    }
}

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
