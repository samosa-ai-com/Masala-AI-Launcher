package com.samosaai.masala.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.samosaai.masala.R
import com.samosaai.masala.data.FontChoice

/** Builds a family from a variable font, pinning the wght axis per weight. */
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(resId: Int, weights: List<Int>) = FontFamily(
    weights.map { w ->
        Font(resId = resId, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** Inter, the text face (OFL; see assets/licenses). */
val Inter = variableFamily(R.font.inter, listOf(300, 400, 500, 600, 650, 700, 800))

/** Space Grotesk, for the clock, big numbers, headings and the wordmark (OFL). */
val SpaceGrotesk = variableFamily(R.font.space_grotesk, listOf(300, 400, 500, 600, 700))

/** Figtree, still selectable in Appearance (OFL). */
val Figtree = variableFamily(R.font.figtree, listOf(300, 400, 500, 600, 650, 700))

/** URLs and step details. A third bundled face isn't worth its size here: the system mono. */
val MasalaMono: FontFamily = FontFamily.Monospace

val SemiBold650 = FontWeight(650)

/**
 * The Masala Glass type scale (design system board): Clock 92/300 in Space Grotesk, Headline 32/700,
 * Title 23/650, Body 15/500, Eyebrow 11/700 tracked out. The M3 slots map onto it so existing screens
 * pick it up unchanged.
 */
fun masalaTypography(choice: FontChoice = FontChoice.INTER): Typography {
    val text = when (choice) {
        FontChoice.INTER -> Inter
        FontChoice.FIGTREE -> Figtree
        FontChoice.SYSTEM -> FontFamily.Default
    }
    val display = if (choice == FontChoice.SYSTEM) FontFamily.Default else SpaceGrotesk
    fun style(family: FontFamily, size: Float, weight: Int, line: Float, tracking: Float = 0f) = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        fontWeight = FontWeight(weight),
        lineHeight = line.sp,
        letterSpacing = tracking.em,
    )
    return Typography(
        displayLarge = style(display, 92f, 300, 96f, -0.03f),
        displayMedium = style(display, 56f, 300, 60f, -0.03f),
        displaySmall = style(display, 40f, 400, 46f, -0.02f),
        headlineLarge = style(text, 32f, 700, 38f, -0.025f),
        headlineMedium = style(text, 28f, 700, 34f, -0.02f),
        headlineSmall = style(text, 24f, 650, 30f, -0.015f),
        titleLarge = style(text, 23f, 650, 29f, -0.017f),
        titleMedium = style(text, 17f, 600, 23f, -0.005f),
        titleSmall = style(text, 15f, 600, 20f),
        bodyLarge = style(text, 16f, 400, 24f),
        bodyMedium = style(text, 15f, 500, 22f),
        bodySmall = style(text, 13f, 400, 18f),
        labelLarge = style(text, 15f, 600, 20f),
        labelMedium = style(text, 12.5f, 600, 16f),
        // The eyebrow: "NEXT · 18:30".
        labelSmall = style(text, 11f, 700, 14f, 0.12f),
    )
}

val Typography = masalaTypography()
