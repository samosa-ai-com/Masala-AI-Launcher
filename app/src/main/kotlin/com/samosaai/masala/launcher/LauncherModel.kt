package com.samosaai.masala.launcher

import android.content.pm.ShortcutInfo
import com.samosaai.masala.core.apps.AppCatalogMerge
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.data.AppOverrideDao
import com.samosaai.masala.data.AppOverrideEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.Collator

/**
 * The installed apps with the user's renames, visibility and pins applied, plus what's pinned to
 * home. Overrides live in Room, so they survive restarts; the catalog itself is always re-read from
 * the system.
 */
class LauncherModel(
    scope: CoroutineScope,
    private val catalog: AppCatalog,
    private val overrides: AppOverrideDao,
    private val actions: AppActions,
) {
    private val rows = overrides.all().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every app in every profile, sorted for the drawer: personal first, then by label. */
    val apps: StateFlow<List<AppEntry>> = combine(catalog.state, rows) { snapshot, rows ->
        val collator = Collator.getInstance()
        AppCatalogMerge.merge(
            snapshot.apps,
            rows.associate { AppKey.parse(it.key) to it.toOverride() },
            Comparator { a, b -> collator.compare(a, b) },
        )
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Apps pinned to home, in the order they were pinned. */
    val pinnedApps: StateFlow<List<AppEntry>> = combine(apps, rows) { apps, rows ->
        val pinnedAt = rows.mapNotNull { row -> row.pinnedAt?.let { row.key to it } }.toMap()
        apps.filter { it.pinned }.sortedBy { pinnedAt[it.key.id] }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val profiles: StateFlow<AppCatalog.Snapshot> = catalog.state

    private val shortcutRefresh = MutableStateFlow(0)

    /** Shortcuts pinned to home, from other apps' "Add to home screen". */
    val pinnedShortcuts: StateFlow<List<ShortcutInfo>> =
        combine(catalog.state, catalog.shortcutsVersion, shortcutRefresh) { _, _, _ -> Unit }
            .map { actions.pinnedShortcuts() }
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Re-reads pinned shortcuts, for example after Masala became the default home app. */
    fun refreshShortcuts() {
        shortcutRefresh.value++
    }

    suspend fun rename(key: AppKey, label: String?) =
        edit(key) { it.copy(label = label?.trim()?.takeIf(String::isNotEmpty)) }

    suspend fun setVisibility(key: AppKey, visibility: Visibility) = edit(key) { it.copy(visibility = visibility) }

    suspend fun setPinned(key: AppKey, pinned: Boolean) =
        edit(key) { it.copy(pinnedAt = if (pinned) it.pinnedAt ?: System.currentTimeMillis() else null) }

    private suspend fun edit(key: AppKey, change: (AppOverrideEntity) -> AppOverrideEntity) {
        val next = change(overrides.get(key.id) ?: AppOverrideEntity.empty(key))
        if (next.isEmpty) overrides.delete(key.id) else overrides.upsert(next)
    }
}
