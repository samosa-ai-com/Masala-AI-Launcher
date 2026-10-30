package com.samosaai.masala.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.samosaai.masala.data.IconShape
import com.samosaai.masala.launcher.IconMask
import com.samosaai.masala.ui.theme.MasalaColors
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.theme.pressScale

/** Masala Glass radii (design system board): chip 10, input 14, icon 21, card 24, pill. */
object Radii {
    val chip = 10.dp
    val input = 14.dp
    val icon = 21.dp
    val card = 24.dp
    val sheet = 28.dp
}

/**
 * Smoked glass: ink at 62% with a 1 px white 10% hairline. Real blur of the wallpaper behind the
 * window isn't available to Compose, so this is translucent only (plan §4, "Real blur").
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radii.card),
    fill: Color = MasalaTheme.tokens.glassFill,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MasalaTheme.tokens
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .then(if (onClick != null) Modifier.pressScale(interaction) else Modifier)
            .clip(shape)
            .background(fill)
            .border(1.dp, tokens.hairline, shape)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** A glass pill: the search-or-ask bar, chips, the mode chip. */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    height: Dp = 52.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = MasalaTheme.tokens
    val shape = RoundedCornerShape(percent = 50)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = height)
            .then(if (onClick != null) Modifier.pressScale(interaction) else Modifier)
            .clip(shape)
            .background(tokens.glassFill)
            .border(1.dp, tokens.hairline, shape)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * A symbol in the same mask as the app icons around it: glass tile, glyph in [tint] (the accent when
 * null). Decorative; the label next to it names it.
 */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color? = null,
    shape: IconShape = IconShape.SQUIRCLE,
    filled: Boolean = false,
) {
    val tokens = MasalaTheme.tokens
    val glyph = tint ?: tokens.accent
    val maskShape = remember(shape) { IconMask.shape(if (shape == IconShape.AS_SHIPPED) IconShape.SQUIRCLE else shape) }
    val fill = if (filled) glyph else glyph.copy(alpha = 0.16f).compositeOver(MasalaColors.Charcoal)
    Box(
        modifier.size(size).clip(maskShape).background(fill).border(1.dp, tokens.hairline, maskShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) MasalaTheme.tokens.onAccent else glyph, modifier = Modifier.size(size * 0.5f))
    }
}

/** A titled card on the theme's ground, for settings and sheets. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radii.card)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MasalaTheme.tokens.hairline, shape)
            .padding(vertical = 8.dp),
    ) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MasalaTheme.tokens.accentText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        content()
    }
}

/** A settings row with a leading icon tile, a title, an optional summary and a trailing slot. */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon, size = 36.dp, tint = tint)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke(this)
    }
}
