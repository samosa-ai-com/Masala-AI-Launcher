package com.samosaai.masala.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Masala Glass colours (plan UI refresh §1), taken from the logo: gold line art on charcoal. Gold on
 * charcoal reads at 9.8:1; on paper it is only 1.5:1, so text and icons on light screens use [DeepGold]
 * while the logo itself stays gold.
 */
object MasalaColors {
    val Gold = Color(0xFFEBCB80)
    val GoldLight = Color(0xFFF3DCA4)
    val DeepGold = Color(0xFF86661C)
    val Charcoal = Color(0xFF242527)
    val Ground = Color(0xFF18191B)
    val Panel = Color(0xFF202124)
    val Paper = Color(0xFFF7F7F7)

    /** Text on dark grounds, used at 100%, 62% and 45%. */
    val Text = Color(0xFFF3F1EC)

    /** Voice: listening, speaking, the mic. */
    val Indigo = Color(0xFF9DB4FF)

    /** Done, connected, quiet nights. */
    val Cardamom = Color(0xFF7FDBA8)

    /** Alerts, delete, errors. */
    val Chili = Color(0xFFFF8A6B)

    /** Icon tints only. */
    val Lilac = Color(0xFFD7A8FF)

    /** Glass panels: ink at 62% with a 1 px white 10% hairline. */
    val GlassInk = Color(0xFF121016)
    const val GLASS_ALPHA = 0.62f
    val Hairline = Color(0x1AFFFFFF)

    // Darker shades of the accents for text and icons on Paper (each at least 4.5:1 there).
    val IndigoDeep = Color(0xFF3651A8)
    val CardamomDeep = Color(0xFF2B7650)
    val ChiliDeep = Color(0xFFB2412A)
    val LilacDeep = Color(0xFF7A3FB0)
}

/** A mode's palette (plan §1: modes carry palettes): ground, card, accent and text. */
data class ModePalette(val ground: Color, val card: Color, val accent: Color, val text: Color) {
    companion object {
        val Morning = ModePalette(Color(0xFFF4EEE3), Color(0xFF161A22), MasalaColors.Gold, Color(0xFF161A22))
        val Evening = ModePalette(Color(0xFF36203F), Color(0x9E120E1A), MasalaColors.Gold, Color(0xFFF6F1EA))
        val QuietNight = ModePalette(Color(0xFF08130E), Color(0xDB0F241B), Color(0xFF8FE3B5), Color(0xFFE3ECE6))
        val Focus = ModePalette(MasalaColors.Ground, MasalaColors.Charcoal, MasalaColors.Indigo, MasalaColors.Text)
    }
}

/** Text drawn straight on the wallpaper: white with a shadow reads on any picture. */
val OnWallpaper = Color.White
val WallpaperShadow = Color(0x99000000)
