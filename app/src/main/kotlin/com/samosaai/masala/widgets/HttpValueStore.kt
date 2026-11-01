package com.samosaai.masala.widgets

import android.util.Log
import com.samosaai.masala.core.contract.HttpBinding
import com.samosaai.masala.data.WidgetHttpValueDao
import com.samosaai.masala.data.WidgetHttpValueEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One cached web value: the display string and when it arrived. */
data class HttpEntry(val value: String, val fetchedAt: Long)

/**
 * Fetched web values (plan `docs/plans/widget-maker.md` §5): `widget_http_values` rows keyed by
 * widget + URL + path, kept in memory for the renderer. Foreground polls and the background
 * worker both write through [refresh]; failures keep the last value with its age.
 */
class HttpValueStore(
    private val scope: CoroutineScope,
    private val dao: WidgetHttpValueDao?,
) {
    private val _entries = MutableStateFlow<Map<String, HttpEntry>>(emptyMap())
    val entries: StateFlow<Map<String, HttpEntry>> = _entries.asStateFlow()

    fun keyFor(widgetId: String, http: HttpBinding): String = "$widgetId\n${http.url}\n${http.jsonPath}"

    fun entry(widgetId: String, http: HttpBinding): HttpEntry? = _entries.value[keyFor(widgetId, http)]

    suspend fun load() {
        val dao = dao ?: return
        val rows = withContext(Dispatchers.IO) { runCatching { dao.all() }.getOrDefault(emptyList()) }
        _entries.value = rows.associate { it.key to HttpEntry(it.value, it.fetchedAt) }
    }

    /** Fetches [http] now; true when a new value landed, false when the old one stands. */
    suspend fun refresh(widgetId: String, http: HttpBinding): Boolean {
        val value = HttpFetch.fetch(http.url, http.jsonPath) ?: return false
        val entry = HttpEntry(value, System.currentTimeMillis())
        _entries.value = _entries.value + (keyFor(widgetId, http) to entry)
        val dao = dao ?: return true
        val key = keyFor(widgetId, http)
        scope.launch(Dispatchers.IO) {
            runCatching { dao.upsert(WidgetHttpValueEntity(key, widgetId, value, entry.fetchedAt)) }
                .onFailure { Log.w(TAG, "HTTP value save failed: $key", it) }
        }
        return true
    }

    suspend fun clearWidget(widgetId: String) {
        _entries.value = _entries.value.filterKeys { !it.startsWith("$widgetId\n") }
        withContext(Dispatchers.IO) { runCatching { dao?.deleteForWidget(widgetId) } }
    }

    companion object {
        private const val TAG = "HttpValueStore"
    }
}
