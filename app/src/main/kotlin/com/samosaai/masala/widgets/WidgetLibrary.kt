package com.samosaai.masala.widgets

import android.util.Log
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.widgets.WidgetTemplates
import com.samosaai.masala.data.WidgetDao
import com.samosaai.masala.data.WidgetEntity
import com.samosaai.masala.data.WidgetVersionDao
import com.samosaai.masala.data.WidgetVersionEntity
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.serializer

/**
 * The widget library (plan `docs/plans/widget-maker.md` §6.1): every saved [WidgetSpec] by id, kept
 * in memory for the resolver and in Room (`widgets` + `widget_versions`, last 20 versions each).
 *
 * [version] changes on every save or delete, so the resolve cache re-resolves without the layout
 * itself changing. Bundled templates seed an empty library once, as `template` rows the user can
 * edit or delete like any other widget.
 */
class WidgetLibrary(
    private val dao: WidgetDao,
    private val versions: WidgetVersionDao,
    /** Which templates this install has been offered; null keeps the old fresh-install-only rule. */
    private val seeds: WidgetSeedStore? = null,
) {
    private val _widgets = MutableStateFlow<Map<String, WidgetSpec>>(emptyMap())
    val widgets: StateFlow<Map<String, WidgetSpec>> = _widgets.asStateFlow()

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    /** The specs, for [com.samosaai.masala.core.migration.WidgetLift] and the resolver. */
    fun current(): Map<String, WidgetSpec> = _widgets.value

    /** One saved widget with who made it, for the manager's My widgets / Templates split. */
    data class LibraryWidget(val spec: WidgetSpec, val createdBy: String)

    /** The library as a flow, decoded; bad rows are skipped like in [load]. */
    fun watch(): kotlinx.coroutines.flow.Flow<List<LibraryWidget>> =
        dao.watch().map { rows ->
            rows.mapNotNull { row ->
                runCatching {
                    LibraryWidget(MasalaJson.strict.decodeFromString(WidgetSpec.serializer(), row.specJson), row.createdBy)
                }.getOrNull()
            }
        }

    /**
     * Loads the library, seeding the bundled templates only on a truly fresh install (no saved
     * layout versions): a user who deleted every widget must not get them all back on restart.
     */
    suspend fun load(seedIfEmpty: Boolean = true) {
        val rows = runCatching { dao.all() }.getOrElse {
            Log.w(TAG, "Widget library unreadable", it)
            return
        }
        if (rows.isEmpty()) {
            if (seedIfEmpty) {
                seedTemplates()
                // Only what actually reached the library counts as offered: a failed save is
                // retried on the next start instead of disappearing forever.
                seeds?.remember(_widgets.value.keys)
            } else {
                // An upgrade can arrive with an empty library (custom widgets only, from before
                // templates seeded) but a saved layout. Remembering every id here marked all of
                // them offered without ever saving one — the bug that left seven templates
                // missing from a real phone. seedNewTemplates saves what was never offered, and
                // nothing when the user really deleted them (that install has bookkeeping).
                seedNewTemplates(emptySet())
            }
            return
        }
        _widgets.value = rows.mapNotNull { row ->
            runCatching { row.id to MasalaJson.strict.decodeFromString(WidgetSpec.serializer(), row.specJson) }
                .onFailure { Log.w(TAG, "Skipping unreadable widget ${row.id}", it) }
                .getOrNull()
        }.toMap()
        _version.value++
        // Not gated on seedIfEmpty: that flag means "this is a fresh install, seed everything".
        // An upgrade with a saved layout passes false, and still needs the templates it was never
        // offered — which is exactly what seedNewTemplates adds, without resurrecting deleted ones.
        seedNewTemplates(rows.map { it.id }.toSet())
        refreshTemplates(rows)
    }

    /**
     * Brings a bundled template row up to date with the JSON this build ships. A template is not a
     * user's widget: when a release improves one (a Pomodoro that chains its break, a fixed URL),
     * the parser change must reach it. Rows the user made (or that the Widget Maker copied) are
     * never touched, and a template the user deleted has no row to refresh.
     */
    private suspend fun refreshTemplates(rows: List<com.samosaai.masala.data.WidgetEntity>) {
        rows.filter { it.createdBy == "template" }.forEach { row ->
            val bundled = runCatching { WidgetTemplates.load(row.id).widget }.getOrNull() ?: return@forEach
            val current = _widgets.value[row.id] ?: return@forEach
            if (bundled != current) save(bundled, createdBy = "template")
        }
    }

    /**
     * Adds the templates this install has never been offered, once (plan `docs/plans/widget-gallery.md`
     * §4). An install from before the seeding bookkeeping is treated as having had the original
     * eight — so a user who deleted one does not get it back, but the new shelves do appear.
     */
    private suspend fun seedNewTemplates(existing: Set<String>) {
        val store = seeds ?: return
        val known = store.known()
        val offered = when {
            known.isNotEmpty() -> known + existing
            // No bookkeeping yet. An install that still has at least one of the original templates
            // was offered them, so a deleted one stays deleted. One with none of them never had
            // them at all (its library was created after the gallery, by custom widgets), and gets
            // the full set — that is the phone where seven templates were missing.
            existing.any { it in WidgetTemplates.legacyIds } -> WidgetTemplates.legacyIds.toSet() + existing
            else -> existing
        }
        val missing = WidgetTemplates.ids.filterNot { it in offered }
        val saved = mutableSetOf<String>()
        missing.forEach { id ->
            runCatching { WidgetTemplates.load(id).widget }.getOrNull()?.let { spec ->
                // save() logs and gives up on its own; only a row that actually landed counts.
                save(spec, createdBy = "template")
                if (id in _widgets.value) saved += id
            }
        }
        // Only templates that reached the library count as offered. Recording a failed save as
        // offered skipped it forever: seven templates (water, habits, next_events, battery_steps,
        // camera_tile, two_cameras, countdown) were missing from a real install that way.
        if (missing.isNotEmpty() || known.isEmpty()) {
            store.remember(
                // Never forget what was already offered: a template the user deleted must stay
                // deleted, and it is only in `known`, not in `existing`.
                (if (known.isEmpty()) WidgetTemplates.legacyIds.toSet() else known) + existing + saved,
            )
        }
    }

    /** Saves [spec] as a new version (Undo in the manager restores [versions]). */
    suspend fun save(spec: WidgetSpec, createdBy: String = "user") {
        val now = System.currentTimeMillis()
        val json = MasalaJson.strict.encodeToString(WidgetSpec.serializer(), spec)
        val existing = runCatching { dao.get(spec.id) }.getOrNull()
        runCatching {
            dao.upsert(
                WidgetEntity(
                    id = spec.id,
                    specJson = json,
                    tier = "spec",
                    title = spec.title,
                    // An explicit user save promotes a template row, so the template refresher
                    // will not revert the user's (or the agent's) edit on the next start.
                    createdBy = if (createdBy == "user") "user" else existing?.createdBy ?: createdBy,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            val last = versions.versions(spec.id).firstOrNull()?.version ?: 0L
            versions.insert(WidgetVersionEntity(spec.id, last + 1, json, now))
            versions.prune(spec.id)
        }.onFailure { Log.w(TAG, "Widget save failed: ${spec.id}", it); return }
        _widgets.value = _widgets.value + (spec.id to spec)
        _version.value++
    }

    suspend fun saveAll(specs: List<WidgetSpec>, createdBy: String = "user") {
        specs.forEach { save(it, createdBy) }
    }

    suspend fun delete(id: String) {
        runCatching {
            dao.delete(id)
            versions.deleteForWidget(id)
        }.onFailure { Log.w(TAG, "Widget delete failed: $id", it); return }
        _widgets.value = _widgets.value - id
        _version.value++
    }

    suspend fun history(id: String): List<WidgetSpec> = runCatching {
        versions.versions(id).mapNotNull { row ->
            runCatching { MasalaJson.strict.decodeFromString(WidgetSpec.serializer(), row.specJson) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private suspend fun seedTemplates() {
        val seeded = WidgetTemplates.ids.mapNotNull { id ->
            runCatching { WidgetTemplates.load(id).widget }.getOrNull()
        }
        if (seeded.isEmpty()) return
        saveAll(seeded, createdBy = "template")
    }

    companion object {
        private const val TAG = "WidgetLibrary"
    }
}
