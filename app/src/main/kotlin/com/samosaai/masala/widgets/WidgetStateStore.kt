package com.samosaai.masala.widgets

import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.data.WidgetStateDao
import com.samosaai.masala.data.WidgetStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * A generated widget's own values (plan `docs/plans/widget-maker.md` W1): counters, checklist ticks,
 * timer deadlines, list lines — one string map per widget id, kept in `widget_state` as JSON.
 *
 * Numbers are stored as plain strings (`"3"`); checklist ticks as a JSON boolean array (`"[true,false]"`);
 * a timer's running deadline as epoch millis; list rows as newline-separated lines. Anything else is
 * read back as-is.
 */
class WidgetStateStore(
    private val scope: CoroutineScope,
    private val dao: WidgetStateDao?,
) {
    private val _states = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val states: StateFlow<Map<String, Map<String, String>>> = _states.asStateFlow()

    fun values(widgetId: String): Map<String, String> = _states.value[widgetId].orEmpty()

    suspend fun load(ids: Collection<String>) {
        val dao = dao ?: return
        val rows = withContext(Dispatchers.IO) { ids.mapNotNull { dao.get(it) } }
        if (rows.isEmpty()) return
        val decoded = rows.mapNotNull { row ->
            runCatching { row.widgetId to MasalaJson.strict.decodeFromString(MapSerializer(String.serializer(), String.serializer()), row.json) }.getOrNull()
        }.toMap()
        if (decoded.isNotEmpty()) _states.value = _states.value + decoded
    }

    fun int(widgetId: String, key: String): Int = values(widgetId)[key]?.toIntOrNull() ?: 0

    fun bools(widgetId: String, key: String, size: Int): List<Boolean> {
        val raw = values(widgetId)[key] ?: return List(size) { false }
        val parsed = runCatching {
            MasalaJson.strict.decodeFromString(BooleanArraySerializer, raw).toList()
        }.getOrNull()
        if (parsed != null) return List(size) { parsed.getOrElse(it) { false } }
        val parts = raw.split(",").map { it.trim().lowercase() in setOf("1", "true", "yes", "x") }
        return List(size) { parts.getOrElse(it) { false } }
    }

    fun set(widgetId: String, key: String, value: String) {
        update(widgetId, values(widgetId) + (key to value))
    }

    fun increment(widgetId: String, key: String, step: Int) {
        set(widgetId, key, (int(widgetId, key) + step).toString())
    }

    fun toggle(widgetId: String, key: String, index: Int, size: Int) {
        val next = bools(widgetId, key, size).toMutableList()
        if (index in next.indices) next[index] = !next[index]
        set(widgetId, key, MasalaJson.strict.encodeToString(BooleanArraySerializer, next.toBooleanArray()))
    }

    private fun update(widgetId: String, map: Map<String, String>) {
        _states.value = _states.value + (widgetId to map)
        val dao = dao ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.upsert(WidgetStateEntity(widgetId, MasalaJson.strict.encodeToString(MapSerializer(String.serializer(), String.serializer()), map), System.currentTimeMillis()))
            }
        }
    }

    companion object {
        private val BooleanArraySerializer = kotlinx.serialization.builtins.BooleanArraySerializer()
    }
}
