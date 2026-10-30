package com.samosaai.masala.ui.theme

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.samosaai.masala.data.Accent
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.data.ThemeMode

/**
 * What Material's scheme doesn't carry: glass fill and hairline, the fixed accents, the three text
 * levels and the mode palette. Read with `MasalaTheme.tokens`.
 */
@Immutable
data class MasalaTokens(
    val dark: Boolean,
    /** The action accent (gold unless the user picked another) and the text drawn on it. */
    val accent: Color,
    val onAccent: Color,
    /** The accent as text or an icon on this theme's ground (deep gold on Paper). */
    val accentText: Color,
    val voice: Color,
    val done: Color,
    val alert: Color,
    val lilac: Color,
    val glassFill: Color,
    val hairline: Color,
    val textHigh: Color,
    val textMedium: Color,
    val textLow: Color,
    val mode: ModePalette,
) {
    companion object {
        fun dark(accent: Color = MasalaColors.Gold) = MasalaTokens(
            dark = true,
            accent = accent,
            onAccent = onColor(accent),
            accentText = accent,
            voice = MasalaColors.Indigo,
            done = MasalaColors.Cardamom,
            alert = MasalaColors.Chili,
            lilac = MasalaColors.Lilac,
            glassFill = MasalaColors.GlassInk.copy(alpha = MasalaColors.GLASS_ALPHA),
            hairline = MasalaColors.Hairline,
            textHigh = MasalaColors.Text,
            textMedium = MasalaColors.Text.copy(alpha = 0.62f),
            textLow = MasalaColors.Text.copy(alpha = 0.45f),
            mode = ModePalette.Evening,
        )

        fun light(accent: Color = MasalaColors.Gold) = MasalaTokens(
            dark = false,
            accent = accent,
            onAccent = onColor(accent),
            accentText = deepen(accent),
            voice = MasalaColors.IndigoDeep,
            done = MasalaColors.CardamomDeep,
            alert = MasalaColors.ChiliDeep,
            lilac = MasalaColors.LilacDeep,
            glassFill = Color.White.copy(alpha = 0.72f),
            hairline = Color(0x1A000000),
            textHigh = MasalaColors.Charcoal,
            textMedium = MasalaColors.Charcoal.copy(alpha = 0.68f),
            textLow = MasalaColors.Charcoal.copy(alpha = 0.52f),
            mode = ModePalette.Morning,
        )

        /** Charcoal on light accents, white on dark ones. */
        fun onColor(c: Color) = if (c.luminance() > 0.4f) MasalaColors.Charcoal else Color.White

        /** The accent as text on Paper: the fixed deep shade for the brand accents, else darkened. */
        private fun deepen(c: Color): Color = when (c) {
            MasalaColors.Gold -> MasalaColors.DeepGold
            MasalaColors.Indigo -> MasalaColors.IndigoDeep
            MasalaColors.Cardamom -> MasalaColors.CardamomDeep
            MasalaColors.Chili -> MasalaColors.ChiliDeep
            MasalaColors.Lilac -> MasalaColors.LilacDeep
            else -> Color.Black.copy(alpha = 0.45f).compositeOver(c)
        }
    }
}

val LocalMasalaTokens = staticCompositionLocalOf { MasalaTokens.dark() }

private fun darkScheme(t: MasalaTokens): ColorScheme = darkColorScheme(
    primary = t.accent,
    onPrimary = t.onAccent,
    primaryContainer = t.accent.copy(alpha = 0.18f).compositeOver(MasalaColors.Ground),
    onPrimaryContainer = MasalaColors.GoldLight,
    secondary = MasalaColors.Indigo,
    onSecondary = MasalaColors.Charcoal,
    // Neutral: Material puts slider tracks and selected chips on it; voice uses its own token.
    secondaryContainer = Color(0xFF2E2F33),
    onSecondaryContainer = MasalaColors.Text,
    tertiary = MasalaColors.Cardamom,
    onTertiary = MasalaColors.Charcoal,
    error = MasalaColors.Chili,
    onError = MasalaColors.Charcoal,
    background = MasalaColors.Ground,
    onBackground = MasalaColors.Text,
    surface = MasalaColors.Ground,
    onSurface = MasalaColors.Text,
    surfaceVariant = MasalaColors.Panel,
    onSurfaceVariant = MasalaColors.Text.copy(alpha = 0.62f).compositeOver(MasalaColors.Ground),
    surfaceContainerLowest = Color(0xFF141517),
    surfaceContainerLow = Color(0xFF1D1E20),
    surfaceContainer = MasalaColors.Panel,
    surfaceContainerHigh = MasalaColors.Charcoal,
    surfaceContainerHighest = Color(0xFF2C2D30),
    outline = MasalaColors.Text.copy(alpha = 0.22f).compositeOver(MasalaColors.Ground),
    outlineVariant = MasalaColors.Text.copy(alpha = 0.10f).compositeOver(MasalaColors.Ground),
    inverseSurface = MasalaColors.Paper,
    inverseOnSurface = MasalaColors.Charcoal,
    inversePrimary = MasalaColors.DeepGold,
)

private fun lightScheme(t: MasalaTokens): ColorScheme = lightColorScheme(
    primary = t.accentText,
    onPrimary = Color.White,
    primaryContainer = MasalaColors.GoldLight,
    onPrimaryContainer = Color(0xFF3B2C07),
    secondary = MasalaColors.IndigoDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E1D6),
    onSecondaryContainer = MasalaColors.Charcoal,
    tertiary = MasalaColors.CardamomDeep,
    onTertiary = Color.White,
    error = MasalaColors.ChiliDeep,
    onError = Color.White,
    background = MasalaColors.Paper,
    onBackground = MasalaColors.Charcoal,
    surface = MasalaColors.Paper,
    onSurface = MasalaColors.Charcoal,
    surfaceVariant = Color(0xFFECEBE8),
    onSurfaceVariant = Color(0xFF55565A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F1EF),
    surfaceContainer = Color(0xFFEFEEEC),
    surfaceContainerHigh = Color(0xFFE8E7E4),
    surfaceContainerHighest = Color(0xFFE1E0DC),
    outline = Color(0xFF8F8E8A),
    outlineVariant = Color(0xFFD3D1CC),
    inverseSurface = MasalaColors.Charcoal,
    inverseOnSurface = MasalaColors.Text,
    inversePrimary = MasalaColors.Gold,
)

/** The accent colour for [accent]; [Accent.WALLPAPER] reads the wallpaper's colours (no permission). */
fun accentColor(context: Context, accent: Accent): Color = when (accent) {
    Accent.GOLD -> MasalaColors.Gold
    Accent.INDIGO -> MasalaColors.Indigo
    Accent.CARDAMOM -> MasalaColors.Cardamom
    Accent.CHILI -> MasalaColors.Chili
    Accent.LILAC -> MasalaColors.Lilac
    Accent.WALLPAPER -> wallpaperAccent(context) ?: MasalaColors.Gold
}

private fun wallpaperAccent(context: Context): Color? = runCatching {
    val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return null
    val argb = (colors.secondaryColor ?: colors.primaryColor).toArgb()
    // Lifted so it reads on charcoal like the gold does.
    val c = Color(argb)
    if (c.luminance() < 0.35f) Color.White.copy(alpha = 0.45f).compositeOver(c) else c
}.getOrNull()

/** Whether [settings] ask for the dark scheme. */
@Composable
fun isDark(settings: LauncherSettings): Boolean = when (settings.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Masala Glass (plan UI refresh U1): dark by default, Paper as the light scheme, one accent, Inter and
 * Space Grotesk, plus [MasalaTokens], motion and haptics. [LauncherSettings.textScale] multiplies the
 * system font size. With [systemBars], the status and navigation bar icons follow the scheme (home sets
 * its own, since it's on the wallpaper).
 */
@Composable
fun MasalaTheme(settings: LauncherSettings, systemBars: Boolean = true, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    if (systemBars) SystemBars(lightBackground = !dark)
    val context = LocalContext.current
    val accent = remember(settings.accent) { accentColor(context, settings.accent) }
    val tokens = remember(dark, accent) { if (dark) MasalaTokens.dark(accent) else MasalaTokens.light(accent) }
    val scheme = remember(tokens) { if (dark) darkScheme(tokens) else lightScheme(tokens) }
    val typography = remember(settings.font) { masalaTypography(settings.font) }
    val motion = rememberMotion(settings.motion)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * settings.textScale),
        LocalMasalaTokens provides tokens,
        LocalMotion provides motion,
        LocalHapticsEnabled provides settings.haptics,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** `MasalaTheme.tokens` reads the current [MasalaTokens]. */
object MasalaTheme {
    val tokens: MasalaTokens
        @Composable @ReadOnlyComposable
        get() = LocalMasalaTokens.current
}

/** Bar icons follow the chosen scheme rather than the system's dark mode. */
@Composable
private fun SystemBars(lightBackground: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBackground
            isAppearanceLightNavigationBars = lightBackground
        }
    }
}
