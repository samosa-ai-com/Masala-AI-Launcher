package com.samosaai.masala.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.samosaai.masala.R

// From the private prototype's ui/theme/Type.kt.

/** Builds a family from a variable font, pinning the wght axis per weight. */
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(resId: Int) = FontFamily(
    listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(resId = resId, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

/** Figtree, the app-wide typeface (OFL; see assets/licenses). */
val Figtree = variableFamily(R.font.figtree)

private val baseline = Typography()

val Typography = Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = Figtree),
    displayMedium = baseline.displayMedium.copy(fontFamily = Figtree),
    displaySmall = baseline.displaySmall.copy(fontFamily = Figtree),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = Figtree),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = Figtree),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = Figtree),
    titleLarge = baseline.titleLarge.copy(fontFamily = Figtree),
    titleMedium = baseline.titleMedium.copy(fontFamily = Figtree),
    titleSmall = baseline.titleSmall.copy(fontFamily = Figtree),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = Figtree),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = Figtree),
    bodySmall = baseline.bodySmall.copy(fontFamily = Figtree),
    labelLarge = baseline.labelLarge.copy(fontFamily = Figtree),
    labelMedium = baseline.labelMedium.copy(fontFamily = Figtree),
    labelSmall = baseline.labelSmall.copy(fontFamily = Figtree),
)
