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
)

/** The bundled templates, read from `masala/widgets/<id>.json` on the classpath (shared with the app). */
object WidgetTemplates {
    /** Kept by hand: a classpath folder can't be listed on Android. A test checks each one. */
    val ids: List<String> = listOf(
        "water", "habits", "next_events", "battery_steps", "camera_tile", "two_cameras", "countdown", "price_from_url",
    )

    fun text(id: String): String =
        checkNotNull(WidgetTemplates::class.java.getResource("/masala/widgets/$id.json")) { "Missing widget template $id" }.readText()

    fun load(id: String): WidgetTemplate = MasalaJson.strict.decodeFromString(WidgetTemplate.serializer(), text(id))

    fun all(): List<WidgetTemplate> = ids.map(::load)
}
