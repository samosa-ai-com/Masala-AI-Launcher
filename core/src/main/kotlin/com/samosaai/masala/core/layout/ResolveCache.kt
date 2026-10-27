package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.ModePatch

/**
 * `resolve(base, patches, snapshot)` remembered on (layout version, patches, snapshot), so a recomposition
 * or a context tick that changed nothing the rules read doesn't merge and evaluate again (plan Phase 2).
 * Keeps the last few results; not thread-safe, call it from one place.
 */
class ResolveCache(private val capacity: Int = 4) {
    private data class Key(val version: Long, val modes: List<ModePatch>, val snapshot: ContextSnapshot)

    private val entries = object : LinkedHashMap<Key, ResolvedLayout>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ResolvedLayout>) = this.size > capacity
    }

    /** How many times the layout was really resolved, for tests. */
    var misses = 0
        private set

    fun resolve(version: Long, base: LayoutDoc, modes: List<ModePatch>, snapshot: ContextSnapshot): ResolvedLayout =
        entries.getOrPut(Key(version, modes, snapshot)) {
            misses++
            LayoutResolver.resolve(base, modes, snapshot)
        }
}
