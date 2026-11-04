package com.samosaai.masala.widgets

import android.content.Context
import com.samosaai.masala.core.contract.HttpBinding
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetSpec

/**
 * Host approval for web values: a widget may fetch a host
 * only once the user said yes; a changed host asks again. Approvals are per widget id and host,
 * in SharedPreferences — not in the spec, never in a prompt.
 */
object HttpApproval {
    /** One fetch a widget would do: who, where, how often, and whether it runs in background. */
    data class Fetch(val widgetId: String, val host: String, val every: Int, val background: Boolean, val http: HttpBinding)

    /** Every `http` a spec would fetch, walking rows and columns too. */
    fun fetchesOf(spec: WidgetSpec): List<Fetch> {
        val https = mutableListOf<HttpBinding>()
        fun walk(component: WidgetComponent) {
            when (component) {
                is WidgetComponent.Text -> component.binding?.http?.let(https::add)
                is WidgetComponent.Progress -> component.binding.http?.let(https::add)
                is WidgetComponent.Chart -> component.binding.http?.let(https::add)
                is WidgetComponent.ListRows -> component.binding.http?.let(https::add)
                is WidgetComponent.Gauge -> component.binding.http?.let(https::add)
                is WidgetComponent.Row -> component.children.forEach(::walk)
                is WidgetComponent.Column -> component.children.forEach(::walk)
                is WidgetComponent.WebValue -> https.add(component.http)
                else -> Unit
            }
        }
        spec.components.forEach(::walk)
        return https.mapNotNull { http ->
            hostOf(http.url)?.let { Fetch(spec.id, it, http.every, http.background, http) }
        }
    }

    /** Fetches whose host [approved] doesn't cover yet. */    fun needingApproval(spec: WidgetSpec, approved: Set<String>): List<Fetch> =
        fetchesOf(spec).filter { it.host !in approved }.distinctBy { it.host }

    fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } }.getOrNull()

    /** One line per host for the gate and the dialogs: what the user is agreeing to. */
    fun describe(fetches: List<Fetch>): String = fetches.distinctBy { it.host }.joinToString("\n") { fetch ->
        val cadence = if (fetch.background) "every ${fetch.every}s on screen and in background" else "every ${fetch.every}s while shown"
        "Fetch ${fetch.host} $cadence."
    }
}

/** The approved hosts per widget id. */
class HttpApprovalStore(context: Context) {
    private val prefs = context.getSharedPreferences("http_approval", Context.MODE_PRIVATE)

    fun approved(widgetId: String): Set<String> =
        prefs.getStringSet(widgetId, emptySet()).orEmpty()

    fun approve(widgetId: String, hosts: Collection<String>) {
        prefs.edit().putStringSet(widgetId, approved(widgetId) + hosts).apply()
    }

    fun clearWidget(widgetId: String) {
        prefs.edit().remove(widgetId).apply()
    }
}
