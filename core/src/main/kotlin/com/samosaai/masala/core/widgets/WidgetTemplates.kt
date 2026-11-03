package com.samosaai.masala.core.widgets

import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.WidgetSpec
import kotlinx.serialization.Serializable

/**
 * A starting point for a widget (plan `docs/plans/widget-maker.md` §6.3): shown in the Add widget
 * sheet and named to the Widget Maker by its one-line [description].
 */
@Serializable
data class WidgetTemplate(
    val id: String,
    val name: String,
    val description: String,
    val widget: WidgetSpec,
    /** Gallery shelf: "Focus", "Health", "Weather"… Defaults so older JSON still loads. */
    val category: String = "Other",
)

/** The bundled templates, read from `masala/widgets/<id>.json` on the classpath (shared with the app). */
object WidgetTemplates {
    /** Kept by hand: a classpath folder can't be listed on Android. A test checks each one. */
    /** The templates every install has always had; an upgrade knows it was offered these. */
    val legacyIds: List<String> = listOf(
        "water", "habits", "next_events", "battery_steps", "camera_tile", "two_cameras", "countdown", "price_from_url",
    )

    /** Everything shipped today: the original eight plus the gallery's shelves. */
    val ids: List<String> = legacyIds + listOf(
        // Focus, calendar, health, weather, money, home, fun, and Masala's own data.
        "pomodoro", "break_timer", "focus_streak", "reading_timer", "inbox_zero",
        "next_meeting", "week_plan", "countdown_days",
        "sleep_last_night", "stand_hourly", "step_globe",
        "weather_globe", "rain_soon",
        "stock_ticker", "crypto_ticker", "budget_month", "subscription_countdown",
        "battery_globe", "urgent_inbox",
        "dice", "coin_flip",
        "second_brain", "quick_capture", "memory_recall", "daily_brief",
    )

    /** Gallery shelves, in the order they are shown; a template names one of these. */
    val categories: List<String> =
        listOf("Focus", "Calendar", "Health", "Weather", "Money", "Home", "Media", "Fun", "Masala", "Other")

    fun text(id: String): String =
        checkNotNull(WidgetTemplates::class.java.getResource("/masala/widgets/$id.json")) { "Missing widget template $id" }.readText()

    fun load(id: String): WidgetTemplate = MasalaJson.strict.decodeFromString(WidgetTemplate.serializer(), text(id))

    fun all(): List<WidgetTemplate> = ids.map(::load)
}
