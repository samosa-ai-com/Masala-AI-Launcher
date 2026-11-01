package com.samosaai.masala.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samosaai.masala.ui.theme.OnWallpaper
import com.samosaai.masala.ui.theme.WallpaperShadow

/** Text drawn straight on the wallpaper. */
@Composable
fun wallpaperText(style: TextStyle): TextStyle =
    style.copy(color = OnWallpaper, shadow = Shadow(WallpaperShadow, Offset(0f, 2f), blurRadius = 6f))

/**
 * One app or shortcut: icon and label, tap to open, long-press for its menu. At least 48dp tall for
 * touch; TalkBack offers the long-press action by [longClickLabel].
 */
@Composable
fun LauncherRow(
    label: String,
    icon: ImageBitmap?,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    longClickLabel: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dimmed: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
    iconSize: Int = 40,
    /** Draws the icon instead of [icon]: a chosen icon isn't a bitmap. */
    iconContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .alpha(if (dimmed) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(iconSize.dp)) {
            // Decorative: the label next to it names the app.
            if (iconContent != null) iconContent() else if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(iconSize.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
