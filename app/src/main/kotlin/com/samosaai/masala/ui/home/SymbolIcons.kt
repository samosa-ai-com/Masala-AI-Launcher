package com.samosaai.masala.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What a `symbol` icon (`IconRef.Symbol`) draws: the Material Symbol of that name, rounded, from
 * `material-icons-extended` (plan UI refresh U1; emoji are gone). Layouts only name the symbol, so this
 * mapping can change without touching them; a test checks every name in `KnownSymbols` has one.
 * Vectors are built on first use, not when the map is.
 */
@Suppress("DEPRECATION") // Chat and QueueMusic have AutoMirrored twins; the plain ones read the same here.
object SymbolIcons {
    private val icons: Map<String, () -> ImageVector> = mapOf(
        "alarm" to { Icons.Rounded.Alarm }, "apps" to { Icons.Rounded.Apps },
        "battery_full" to { Icons.Rounded.BatteryFull }, "bedtime" to { Icons.Rounded.Bedtime },
        "book" to { Icons.Rounded.Book }, "calendar_today" to { Icons.Rounded.CalendarToday },
        "call" to { Icons.Rounded.Call }, "camera" to { Icons.Rounded.CameraAlt },
        "chat" to { Icons.Rounded.Chat }, "checklist" to { Icons.Rounded.Checklist },
        "cloud" to { Icons.Rounded.Cloud }, "directions_car" to { Icons.Rounded.DirectionsCar },
        "email" to { Icons.Rounded.Email }, "event" to { Icons.Rounded.Event },
        "fitness_center" to { Icons.Rounded.FitnessCenter }, "flag" to { Icons.Rounded.Flag },
        "folder" to { Icons.Rounded.Folder }, "headphones" to { Icons.Rounded.Headphones },
        "home" to { Icons.Rounded.Home }, "info" to { Icons.Rounded.Info },
        "language" to { Icons.Rounded.Language }, "location_on" to { Icons.Rounded.LocationOn },
        "mic" to { Icons.Rounded.Mic }, "music_note" to { Icons.Rounded.MusicNote },
        "notifications" to { Icons.Rounded.Notifications }, "photo" to { Icons.Rounded.Photo },
        "play_arrow" to { Icons.Rounded.PlayArrow }, "priority_high" to { Icons.Rounded.PriorityHigh },
        "queue_music" to { Icons.Rounded.QueueMusic }, "restaurant" to { Icons.Rounded.Restaurant },
        "school" to { Icons.Rounded.School }, "search" to { Icons.Rounded.Search },
        "settings" to { Icons.Rounded.Settings }, "shopping_cart" to { Icons.Rounded.ShoppingCart },
        "sports_esports" to { Icons.Rounded.SportsEsports }, "star" to { Icons.Rounded.Star },
        "timer" to { Icons.Rounded.Timer }, "tune" to { Icons.Rounded.Tune },
        "wb_sunny" to { Icons.Rounded.WbSunny }, "widgets" to { Icons.Rounded.Widgets },
        "work" to { Icons.Rounded.Work },
        "calculate" to { Icons.Rounded.Calculate },
        "contacts" to { Icons.Rounded.Contacts },
        "flight" to { Icons.Rounded.Flight },
        "map" to { Icons.Rounded.Map },
        "movie" to { Icons.Rounded.Movie },
        "newspaper" to { Icons.Rounded.Newspaper },
        "palette" to { Icons.Rounded.Palette },
        "pets" to { Icons.Rounded.Pets },
        "public" to { Icons.Rounded.Public },
        "radio" to { Icons.Rounded.Radio },
        "sports_soccer" to { Icons.Rounded.SportsSoccer },
        "task_alt" to { Icons.Rounded.TaskAlt },
        "translate" to { Icons.Rounded.Translate },
        "videogame_asset" to { Icons.Rounded.VideogameAsset },
        "wallet" to { Icons.Rounded.AccountBalanceWallet },
        "wifi" to { Icons.Rounded.Wifi },
    )

    val names: Set<String> get() = icons.keys

    /** A name no layout should use, but one from a newer version still draws a plain dot. */
    val Fallback: ImageVector get() = Icons.Rounded.Circle

    fun icon(name: String): ImageVector = icons[name]?.invoke() ?: Fallback

    fun has(name: String): Boolean = name in icons

    /** The palette names a layout may use as a tint; anything else gets the theme accent. */
    fun tint(name: String?): Color? = when (name) {
        "orange" -> Color(0xFFF2A65A)
        "teal" -> Color(0xFF6FD3C8)
        "blue" -> Color(0xFF9DB4FF)
        "green" -> Color(0xFF7FDBA8)
        "red" -> Color(0xFFFF8A6B)
        "purple" -> Color(0xFFD7A8FF)
        "pink" -> Color(0xFFF59AC0)
        "yellow" -> Color(0xFFEBCB80)
        "gray" -> Color(0xFFB9B7B2)
        else -> null
    }
}
