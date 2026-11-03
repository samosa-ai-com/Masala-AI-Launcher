package com.samosaai.masala.widgets

import com.samosaai.masala.core.contract.BindingSource
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetPrefs
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.contract.componentsWithPaths
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * Time-based resets a widget asks for in its settings (plan
 * `docs/plans/widgets-functional-pass.md` §3): a water counter starts the day at zero, a habit
 * checklist renews each morning, a budget resets on the first of the month.
 *
 * A marker (`@reset.<path>`) records the period the value was last cleared for, so this runs safely
 * on every composition and only writes when a new day, week or month has begun.
 */
object WidgetRules {

    fun apply(
        store: WidgetStateStore,
        widgetId: String,
        spec: WidgetSpec,
        prefs: WidgetPrefs,
        today: LocalDate = LocalDate.now(),
    ) {
        spec.componentsWithPaths().forEach { (path, component) ->
            val reset = prefs.components[path]?.reset
                ?: when (component) {
                    is WidgetComponent.Counter -> component.reset
                    is WidgetComponent.Checklist -> component.reset
                    is WidgetComponent.Progress -> component.reset
                    else -> null
                }
            // "never" is an explicit override of a template's default (null means "inherit").
            if (reset == null || reset == "never") return@forEach
            val period = periodKey(reset, today) ?: return@forEach
            val marker = "@reset.$path"
            val values = store.values(widgetId)
            if (values[marker] == period) return@forEach
            when (component) {
                // A countdown is derived from its date, so there is nothing to clear.
                is WidgetComponent.Counter -> {
                    if (component.date == null) store.set(widgetId, component.key, "0")
                }
                is WidgetComponent.Checklist ->
                    store.set(widgetId, component.key, component.items.joinToString(",") { "false" })
                is WidgetComponent.Progress -> {
                    val binding = component.binding
                    val key = binding.key
                    if (binding.source == BindingSource.STORAGE && key != null) store.set(widgetId, key, "0")
                }
                else -> Unit
            }
            // Always record the period, even when the value was already at the reset state: a
            // missing marker is re-evaluated on the next composition and would wipe a value the
            // user entered later the same day.
            store.set(widgetId, marker, period)
        }
    }

    /** The key that changes when the next period starts; null for "never". */
    fun periodKey(reset: String, today: LocalDate): String? = when (reset) {
        "daily" -> today.toString()
        "weekly" -> "${today.get(IsoFields.WEEK_BASED_YEAR)}-W${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
        "monthly" -> "${today.year}-${today.monthValue.toString().padStart(2, '0')}"
        else -> null
    }
}
