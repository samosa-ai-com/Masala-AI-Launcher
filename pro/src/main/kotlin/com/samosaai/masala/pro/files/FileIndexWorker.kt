package com.samosaai.masala.pro.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.samosaai.masala.core.files.TextExtractor
import com.samosaai.masala.pro.R
import com.samosaai.masala.pro.data.ProDatabase
import kotlinx.coroutines.CancellationException

/**
 * One file index run (plan Phase 3 Lane B): daily while charging, or Index now. Foreground with a
 * progress notification; when the system stops it, it stops between files and the next run carries on.
 */
class FileIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val index = ProFileIndex.get(context)

    override suspend fun getForegroundInfo(): ForegroundInfo = info(0, 0)

    override suspend fun doWork(): Result {
        if (!Environment.isExternalStorageManager()) {
            index.onFinished(null, "Needs all-files access")
            return Result.success()
        }
        val extractor = index.extractor ?: return Result.retry()
        if (!index.begin()) return Result.success()
        try {
            return runIndex(extractor)
        } finally {
            index.end()
        }
    }

    private suspend fun runIndex(extractor: TextExtractor): Result {
        val generation = index.currentGeneration
        // From the background Android may refuse a foreground service; the run still goes on.
        runCatching { setForeground(info(0, 0)) }.onFailure { Log.w(TAG, "No foreground service: ${it.message}") }
        index.onProgress(0, 0)
        return try {
            var lastShown = 0L
            val result = FileIndexer(RoomIndexStore(ProDatabase.get(applicationContext)), extractor).run(
                root = Environment.getExternalStorageDirectory(),
                progress = { done, total ->
                    index.onProgress(done, total)
                    val now = System.currentTimeMillis()
                    if (now - lastShown > 1_000) {
                        lastShown = now
                        runCatching { setForegroundAsync(info(done, total)) }
                    }
                },
                isStopped = { isStopped || index.currentGeneration != generation },
            )
            index.onFinished(result, null, generation)
            Result.success()
        } catch (e: CancellationException) {
            index.onFinished(null, null, generation)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Index run failed", e)
            index.onFinished(null, e.message ?: e.javaClass.simpleName, generation)
            Result.failure()
        }
    }

    private fun info(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.file_index_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val text = if (total == 0) applicationContext.getString(R.string.file_index_counting)
        else applicationContext.getString(R.string.file_index_progress, done, total)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationContext.getString(R.string.file_index_running))
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        // The dataSync type is declared on WorkManager's SystemForegroundService in the app
        // module's manifest (pro is never an APK on its own); standalone pro lint can't see it.
        //noinspection SpecifyForegroundServiceType
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "FileIndexWorker"
        private const val CHANNEL = "file_index"
        private const val NOTIFICATION_ID = 4201
    }
}
