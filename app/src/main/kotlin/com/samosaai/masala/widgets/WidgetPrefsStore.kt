package com.samosaai.masala.widgets

import com.samosaai.masala.core.contract.ComponentPrefs
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.WidgetPrefs

/**
 * A widget's own settings, kept in its existing state map under [KEY]. No new table: the state map already
 * survives
 * restarts per widget id, and `WidgetSpec.resolved` applies these to a copy before drawing.
 */
object WidgetPrefsStore {
    const val KEY = "@prefs"

    fun of(state: Map<String, String>): WidgetPrefs =
        state[KEY]?.let { raw ->
            runCatching { MasalaJson.strict.decodeFromString(WidgetPrefs.serializer(), raw) }.getOrNull()
        } ?: WidgetPrefs()

    fun save(store: WidgetStateStore, widgetId: String, prefs: WidgetPrefs) {
        store.set(widgetId, KEY, MasalaJson.strict.encodeToString(WidgetPrefs.serializer(), prefs))
    }

    fun update(store: WidgetStateStore, widgetId: String, change: (WidgetPrefs) -> WidgetPrefs) {
        save(store, widgetId, change(of(store.values(widgetId))))
    }

    /** One component's settings, merging [change] over whatever is already set there. */
    fun update(
        store: WidgetStateStore,
        widgetId: String,
        path: String,
        change: (ComponentPrefs) -> ComponentPrefs,
    ) = update(store, widgetId) { prefs ->
        prefs.copy(components = prefs.components + (path to change(prefs.components[path] ?: ComponentPrefs())))
    }
}
