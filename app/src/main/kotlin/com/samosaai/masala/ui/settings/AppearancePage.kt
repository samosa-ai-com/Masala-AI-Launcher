package com.samosaai.masala.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.samosaai.masala.R
import com.samosaai.masala.data.Accent
import com.samosaai.masala.data.Character
import com.samosaai.masala.data.FontChoice
import com.samosaai.masala.data.IconShape
import com.samosaai.masala.data.IconStyle
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.data.MotionLevel
import com.samosaai.masala.data.ThemeMode
import com.samosaai.masala.launcher.IconMask
import com.samosaai.masala.ui.character.CharacterState
import com.samosaai.masala.ui.character.MasalaCharacter
import com.samosaai.masala.ui.drawer.Segmented
import com.samosaai.masala.ui.theme.Figtree
import com.samosaai.masala.ui.theme.Inter
import com.samosaai.masala.ui.theme.MasalaColors
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.theme.SpaceGrotesk
import com.samosaai.masala.ui.theme.accentColor
import com.samosaai.masala.ui.theme.rememberHaptics
import kotlin.math.roundToInt

/**
 * Settings → Look & feel → Appearance (plan UI refresh U5, appearance board): a live preview, then
 * theme, accent, typeface, text size, icon shape and style, animations, character and haptics. Every
 * change applies at once, so the preview and the page itself show it.
 */
@Composable
fun AppearancePage(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit, onBack: () -> Unit) {
    val haptics = rememberHaptics()
    fun set(change: (LauncherSettings) -> LauncherSettings) {
        haptics.tick()
        update(change)
    }
    SettingsScaffold(stringResource(R.string.appearance_title), onBack) {
        Preview(settings)

        Eyebrow(stringResource(R.string.appearance_theme))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeCard(stringResource(R.string.appearance_glass), MasalaColors.Panel, MasalaColors.Gold, Color.White.copy(alpha = 0.18f), settings.theme == ThemeMode.DARK, Modifier.weight(1f)) { set { it.copy(theme = ThemeMode.DARK) } }
            ThemeCard(stringResource(R.string.appearance_paper), Color(0xFFF4EEE3), Color(0xFF161A22), MasalaColors.Gold, settings.theme == ThemeMode.LIGHT, Modifier.weight(1f)) { set { it.copy(theme = ThemeMode.LIGHT) } }
            ThemeCard(stringResource(R.string.appearance_follow_phone), Color(0xFF36203F), Color(0xFFD9824C), Color(0xFF8E4548), settings.theme == ThemeMode.SYSTEM, Modifier.weight(1f)) { set { it.copy(theme = ThemeMode.SYSTEM) } }
        }

        Eyebrow(stringResource(R.string.appearance_accent))
        val context = LocalContext.current
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Accent.entries.forEach { a ->
                val on = settings.accent == a
                val label = accentLabel(a)
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .then(if (a == Accent.WALLPAPER) Modifier.border(1.dp, MasalaTheme.tokens.textLow, CircleShape) else Modifier.background(accentColor(context, a)))
                        .border(if (on) 3.dp else 0.dp, if (on) MasalaTheme.tokens.textHigh else Color.Transparent, CircleShape)
                        .clickable(role = Role.RadioButton) { set { it.copy(accent = a) } }
                        .semantics { contentDescription = label; selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    if (a == Accent.WALLPAPER) Icon(Icons.Rounded.Wallpaper, null, tint = MasalaTheme.tokens.textHigh, modifier = Modifier.size(18.dp))
                }
            }
        }

        Eyebrow(stringResource(R.string.appearance_typeface))
        Card {
            FontRow(Inter, stringResource(R.string.appearance_font_inter), stringResource(R.string.appearance_font_inter_note), settings.font == FontChoice.INTER) { set { it.copy(font = FontChoice.INTER) } }
            FontRow(Figtree, stringResource(R.string.appearance_font_figtree), stringResource(R.string.appearance_font_figtree_note), settings.font == FontChoice.FIGTREE) { set { it.copy(font = FontChoice.FIGTREE) } }
            FontRow(FontFamily.Default, stringResource(R.string.appearance_font_system), stringResource(R.string.appearance_font_system_note), settings.font == FontChoice.SYSTEM) { set { it.copy(font = FontChoice.SYSTEM) } }
        }
        // Saved when the finger lifts, so the whole screen doesn't re-layout on every step.
        var scale by remember(settings.textScale) { mutableFloatStateOf(settings.textScale) }
        val scaleLabel = stringResource(R.string.settings_text_scale, (scale * 100).roundToInt())
        Text(scaleLabel, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = scale, onValueChange = { scale = it }, onValueChangeFinished = { update { it.copy(textScale = scale) } },
            valueRange = LauncherSettings.MIN_TEXT_SCALE..LauncherSettings.MAX_TEXT_SCALE, steps = 5,
            modifier = Modifier.semantics { contentDescription = scaleLabel },
        )

        Eyebrow(stringResource(R.string.appearance_icons))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconShape.entries.forEach { shape ->
                val on = settings.iconShape == shape
                Column(
                    Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (on) MasalaTheme.tokens.accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(BorderStroke(1.5.dp, if (on) MasalaTheme.tokens.accent else MasalaTheme.tokens.hairline), RoundedCornerShape(18.dp))
                        .clickable(role = Role.RadioButton) { set { it.copy(iconShape = shape) } }.semantics { selected = on },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                ) {
                    Box(Modifier.size(30.dp).clip(IconMask.shape(shape)).background(if (on) MasalaTheme.tokens.accent else MasalaTheme.tokens.textMedium))
                    Text(iconShapeLabel(shape), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Segmented(listOf(stringResource(R.string.appearance_icons_original), stringResource(R.string.appearance_icons_tinted)), if (settings.iconStyle == IconStyle.TINTED) 1 else 0) { i ->
            set { it.copy(iconStyle = if (i == 1) IconStyle.TINTED else IconStyle.ORIGINAL) }
        }

        Eyebrow(stringResource(R.string.appearance_motion))
        Card(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            LabeledRow(stringResource(R.string.appearance_animations)) {
                Segmented(listOf(stringResource(R.string.appearance_full), stringResource(R.string.appearance_reduced)), if (settings.motion == MotionLevel.REDUCED) 1 else 0) { i ->
                    set { it.copy(motion = if (i == 1) MotionLevel.REDUCED else MotionLevel.FULL) }
                }
            }
            LabeledRow(stringResource(R.string.appearance_character)) {
                Segmented(listOf("Sam", stringResource(R.string.appearance_orb), stringResource(R.string.appearance_hidden)), Character.entries.indexOf(settings.character)) { i ->
                    set { it.copy(character = Character.entries[i]) }
                }
            }
            SettingsToggleRow(stringResource(R.string.appearance_haptics), settings.haptics) { on -> update { it.copy(haptics = on) } }
        }
    }
}

/** The preview (appearance board): clock, an action, three icon tiles and the character. */
@Composable
private fun Preview(settings: LauncherSettings) {
    val tokens = MasalaTheme.tokens
    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF2C1B35))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "18:02",
                style = TextStyle(fontFamily = if (settings.font == FontChoice.SYSTEM) FontFamily.Default else SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 46.sp, letterSpacing = (-0.03).em),
                color = MasalaColors.Text,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(tokens.accent).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.appearance_preview_go), color = tokens.onAccent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
                repeat(3) {
                    Box(Modifier.size(34.dp).clip(IconMask.shape(settings.iconShape)).background(tokens.wallpaperGlass).border(1.dp, Color.White.copy(alpha = 0.12f), IconMask.shape(settings.iconShape)))
                }
            }
        }
        MasalaCharacter(settings.character, CharacterState.HELLO, Modifier.align(Alignment.BottomEnd).padding(12.dp).size(72.dp))
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, MasalaTheme.tokens.hairline, shape).then(modifier)) { content() }
}

@Composable
private fun LabeledRow(label: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun ThemeCard(label: String, ground: Color, a: Color, b: Color, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = MasalaTheme.tokens
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(if (on) tokens.accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(BorderStroke(1.5.dp, if (on) tokens.accent else tokens.hairline), RoundedCornerShape(18.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).semantics { selected = on }.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).background(ground).padding(6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(5.dp)).background(a))
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(5.dp)).background(b))
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FontRow(family: FontFamily, title: String, note: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (on) MasalaTheme.tokens.accent.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick).semantics { selected = on }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Aa", fontFamily = family, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(54.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (on) Icon(Icons.Rounded.Check, null, tint = MasalaTheme.tokens.accentText)
    }
}

@Composable
private fun accentLabel(a: Accent) = stringResource(
    when (a) {
        Accent.GOLD -> R.string.accent_gold
        Accent.INDIGO -> R.string.accent_indigo
        Accent.CARDAMOM -> R.string.accent_cardamom
        Accent.CHILI -> R.string.accent_chili
        Accent.LILAC -> R.string.accent_lilac
        Accent.WALLPAPER -> R.string.accent_wallpaper
    },
)

@Composable
private fun iconShapeLabel(s: IconShape) = stringResource(
    when (s) {
        IconShape.SQUIRCLE -> R.string.icon_squircle
        IconShape.CIRCLE -> R.string.icon_circle
        IconShape.SQUARE -> R.string.icon_square
        IconShape.AS_SHIPPED -> R.string.icon_as_shipped
    },
)
