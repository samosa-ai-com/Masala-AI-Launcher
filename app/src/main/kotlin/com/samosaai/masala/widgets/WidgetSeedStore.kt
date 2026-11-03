package com.samosaai.masala.widgets

import android.content.Context

/**
 * Which bundled templates this install has already been offered (plan `docs/plans/widget-gallery.md`
 * §4). Without it a template seeds once, on a fresh install, and an upgrade never shows the new
 * ones; with it, a new template appears once and a deleted one never comes back.
 */
interface WidgetSeedStore {
    fun known(): Set<String>
    fun remember(ids: Set<String>)
}

/** The store on a real device: one string set in its own preferences file. */
class PrefsWidgetSeedStore(context: Context) : WidgetSeedStore {
    private val prefs = context.getSharedPreferences("widget_seeds", Context.MODE_PRIVATE)

    override fun known(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty()

    override fun remember(ids: Set<String>) {
        prefs.edit().putStringSet(KEY, known() + ids).apply()
    }

    private companion object {
        const val KEY = "seeded"
    }
}
