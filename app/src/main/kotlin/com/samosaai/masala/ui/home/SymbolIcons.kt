package com.samosaai.masala.ui.home

import androidx.compose.ui.graphics.Color

/**
 * What a `symbol` icon (`IconRef.Symbol`) shows. The prototype draws each Material Symbol name as an
 * emoji on a tinted disc, which needs no icon font and no extra library (the extended Material icon set
 * is several MB); a test checks every name in `KnownSymbols` has one. Real vector symbols can replace
 * this without touching layouts, since they only name the symbol.
 */
object SymbolIcons {
    private val glyphs = mapOf(
        "alarm" to "⏰", "apps" to "⠿", "battery_full" to "🔋", "bedtime" to "🌙", "book" to "📖",
        "calendar_today" to "📅", "call" to "📞", "camera" to "📷", "chat" to "💬", "checklist" to "✅",
        "cloud" to "☁️", "directions_car" to "🚗", "email" to "✉️", "event" to "🗓️", "fitness_center" to "🏋️",
        "flag" to "⚑", "folder" to "📁", "headphones" to "🎧", "home" to "🏠", "info" to "ℹ️",
        "language" to "🌐", "location_on" to "📍", "mic" to "🎤", "music_note" to "♪", "notifications" to "🔔",
        "photo" to "🖼️", "play_arrow" to "▶", "priority_high" to "❗", "queue_music" to "🎶", "restaurant" to "🍽️",
        "school" to "🎓", "search" to "🔍", "settings" to "⚙️", "shopping_cart" to "🛒", "sports_esports" to "🎮", "star" to "★",
        "timer" to "⏱️", "tune" to "🎚️", "wb_sunny" to "☀️", "widgets" to "▦", "work" to "💼",
    )

    val names: Set<String> get() = glyphs.keys

    fun glyph(name: String): String = glyphs[name] ?: name.take(1).uppercase()

    /** The palette names a layout may use as a tint; anything else gets the theme colour. */
    fun tint(name: String?): Color? = when (name) {
        "orange" -> Color(0xFFE8833A)
        "teal" -> Color(0xFF2A9D8F)
        "blue" -> Color(0xFF3D7BD9)
        "green" -> Color(0xFF4CAF50)
        "red" -> Color(0xFFD64545)
        "purple" -> Color(0xFF8E5BD0)
        "pink" -> Color(0xFFD9578F)
        "yellow" -> Color(0xFFE0B100)
        "gray" -> Color(0xFF7A8087)
        else -> null
    }
}
