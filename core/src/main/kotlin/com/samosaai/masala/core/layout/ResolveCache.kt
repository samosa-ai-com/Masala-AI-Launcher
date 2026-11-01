package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.ModePatch
import com.samosaai.masala.core.contract.WidgetSpec

/**
 * `resolve(base, patches, snapshot)` remembered on (layout version, patches, snapshot), so a recomposition
 * or a context tick that changed nothing the rules read doesn't merge and evaluate again (plan Phase 2).
 * Keeps the last few results; not thread-safe, call it from one place.
 */
class ResolveCache(private val capacity: Int = 4) {
    private data class Key(val version: Long, val modes: List<ModePatch>, val snapshot: ContextSnapshot, val widgetsVersion: Long)

    private val entries = object : LinkedHashMap<Key, ResolvedLayout>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ResolvedLayout>) = this.size > capacity
    }

    /** How many times the layout was really resolved, for tests. */
    var misses = 0
        private set

    /**
     * [widgetsVersion] changes whenever the widget library does (any save or delete), so an edited
     * library widget is drawn without the layout itself changing.
     */
    fun resolve(
        version: Long,
        base: LayoutDoc,
        modes: List<ModePatch>,
        snapshot: ContextSnapshot,
        widgetsVersion: Long = 0,
        widgets: Map<String, WidgetSpec> = emptyMap(),
    ): ResolvedLayout =
        entries.getOrPut(Key(version, modes, snapshot, widgetsVersion)) {
            misses++
            LayoutResolver.resolve(base, modes, snapshot, widgets)
        }
}
