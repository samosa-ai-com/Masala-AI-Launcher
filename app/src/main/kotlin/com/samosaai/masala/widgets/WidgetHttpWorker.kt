package com.samosaai.masala.widgets

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.samosaai.masala.core.contract.HttpBinding
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.data.MasalaDatabase
import java.util.concurrent.TimeUnit

/**
 * Background web values (plan `docs/plans/widget-maker.md` §5): every 15 minutes, each `http`
 * binding with `background` set refreshes into `widget_http_values`. Widgets without one cost a
 * single library read and nothing else; failures keep the last value with its age. No private
 * data rides along: the checker already rejects templated URLs at save time.
 */
class WidgetHttpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val database = runCatching { MasalaDatabase.create(applicationContext) }.getOrNull() ?: return Result.retry()
        return try {
            val specs = database.widgets().all().mapNotNull { row ->
                runCatching { MasalaJson.strict.decodeFromString(WidgetSpec.serializer(), row.specJson) }.getOrNull()
            }
            val store = HttpValueStore(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), database.httpValues())
            store.load()
            specs.flatMap { spec -> HttpApproval.fetchesOf(spec).map { spec.id to bindingFor(spec, it.host) } }
                .distinct()
                .forEach { (widgetId, http) ->
                    if (http.background) store.refresh(widgetId, http)
                }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun bindingFor(spec: WidgetSpec, host: String): HttpBinding {
        val all = mutableListOf<HttpBinding>()
        fun walk(component: com.samosaai.masala.core.contract.WidgetComponent) {
            when (component) {
                is com.samosaai.masala.core.contract.WidgetComponent.WebValue -> all.add(component.http)
                is com.samosaai.masala.core.contract.WidgetComponent.Row -> component.children.forEach(::walk)
                is com.samosaai.masala.core.contract.WidgetComponent.Column -> component.children.forEach(::walk)
                is com.samosaai.masala.core.contract.WidgetComponent.Text -> component.binding?.http?.let(all::add)
                is com.samosaai.masala.core.contract.WidgetComponent.Progress -> component.binding.http?.let(all::add)
                is com.samosaai.masala.core.contract.WidgetComponent.Chart -> component.binding.http?.let(all::add)
                is com.samosaai.masala.core.contract.WidgetComponent.ListRows -> component.binding.http?.let(all::add)
                is com.samosaai.masala.core.contract.WidgetComponent.Gauge -> component.binding.http?.let(all::add)
                else -> Unit
            }
        }
        spec.components.forEach(::walk)
        return all.first { HttpApproval.hostOf(it.url) == host }
    }

    companion object {
        private const val WORK = "widget-http-refresh"

        /** Keeps one 15-minute poll loop armed; the worker itself no-ops without background widgets. */
        fun ensure(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetHttpWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
