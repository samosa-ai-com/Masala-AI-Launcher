package com.samosaai.masala.widgets

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
            val store = HttpValueStore(
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
                database.httpValues(),
            ) { widgetId -> HttpApprovalStore(applicationContext).approved(widgetId) }
            store.load()
            // Every binding refreshes on its own cadence; only approved hosts are fetched
            // (the store enforces it), so an edited URL stays inert until the user allows it.
            specs.flatMap { spec -> HttpApproval.fetchesOf(spec).map { spec.id to it.http } }
                .distinct()
                .forEach { (widgetId, http) ->
                    if (http.background) store.refresh(widgetId, http)
                }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
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
