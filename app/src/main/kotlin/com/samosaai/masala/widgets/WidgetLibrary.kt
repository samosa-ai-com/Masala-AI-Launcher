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

    suspend fun load() {
        val rows = runCatching { dao.all() }.getOrElse {
            Log.w(TAG, "Widget library unreadable", it)
            return
        }
        if (rows.isEmpty()) {
            seedTemplates()
            return
        }
        _widgets.value = rows.mapNotNull { row ->
            runCatching { row.id to MasalaJson.strict.decodeFromString(WidgetSpec.serializer(), row.specJson) }
                .onFailure { Log.w(TAG, "Skipping unreadable widget ${row.id}", it) }
                .getOrNull()
        }.toMap()
        _version.value++
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
                    createdBy = existing?.createdBy ?: createdBy,
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
