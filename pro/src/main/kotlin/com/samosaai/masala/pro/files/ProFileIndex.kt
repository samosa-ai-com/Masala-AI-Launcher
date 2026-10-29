package com.samosaai.masala.pro.files

import android.content.Context
import android.os.Environment
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.samosaai.masala.core.files.FileIndex
import com.samosaai.masala.core.files.IndexStatus
import com.samosaai.masala.core.files.TextExtractor
import com.samosaai.masala.pro.data.ProDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The file index as `:app` sees it (arch §3.10): daily runs while charging, Index now, Clear, and the
 * status the Settings page shows. One per process ([get]); the worker reports into it.
 */
class ProFileIndex private constructor(private val context: Context) : FileIndex {
    private val prefs = context.getSharedPreferences("file_index", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<(IndexStatus) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work get() = WorkManager.getInstance(context)

    /** Set by `ProTools.register`; the worker reads text through it. */
    @Volatile var extractor: TextExtractor? = null

    @Volatile private var progress: Pair<Int, Int>? = null

    /** The daily run and Index now are separate unique works: only one of them walks at a time. */
    private val running = AtomicBoolean(false)

    /** Bumped by [clear]; a run that started before it stops and doesn't report. */
    private val generation = AtomicInteger(0)

    private val hasAccess get() = Environment.isExternalStorageManager()

    override val status: IndexStatus
        get() = IndexStatus(
            state = when {
                !hasAccess -> IndexStatus.State.NEEDS_ACCESS
                progress != null -> IndexStatus.State.RUNNING
                else -> IndexStatus.State.IDLE
            },
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            done = progress?.first ?: 0,
            total = progress?.second ?: 0,
            files = prefs.getInt(KEY_FILES, 0),
            withText = prefs.getInt(KEY_WITH_TEXT, 0),
            failures = prefs.getInt(KEY_FAILURES, 0),
            lastRunAt = prefs.getLong(KEY_LAST_RUN, 0L).takeIf { it > 0 },
            message = prefs.getString(KEY_MESSAGE, null),
        )

    override fun observe(listener: (IndexStatus) -> Unit): () -> Unit {
        listeners += listener
        listener(status)
        return { listeners -= listener }
    }

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).putBoolean(KEY_DECIDED, true).apply()
        if (enabled) schedule() else work.cancelUniqueWork(PERIODIC)
        publish()
    }

    override fun indexNow() {
        val request = OneTimeWorkRequestBuilder<FileIndexWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    override fun clear() {
        generation.incrementAndGet()
        work.cancelUniqueWork(NOW)
        work.cancelUniqueWork(PERIODIC)
        if (prefs.getBoolean(KEY_ENABLED, false)) schedule()
        scope.launch {
            ProDatabase.get(context).fileIndex().clear()
            prefs.edit().remove(KEY_FILES).remove(KEY_WITH_TEXT).remove(KEY_FAILURES).remove(KEY_LAST_RUN).remove(KEY_MESSAGE).apply()
            publish()
        }
    }

    override fun refresh() {
        // The first time access is seen, daily runs go on and a first run starts (plan decision 4).
        if (hasAccess && !prefs.getBoolean(KEY_DECIDED, false)) {
            setEnabled(true)
            indexNow()
        } else {
            publish()
        }
    }

    /** Claims the run; false while another worker is indexing. Pair with [end]. */
    internal fun begin(): Boolean = running.compareAndSet(false, true)

    internal fun end() = running.set(false)

    internal val currentGeneration: Int get() = generation.get()

    internal fun onProgress(done: Int, total: Int) {
        progress = done to total
        publish()
    }

    internal fun onFinished(result: FileIndexer.Result?, error: String?, startedIn: Int = currentGeneration) {
        progress = null
        if (startedIn != currentGeneration) return publish()
        prefs.edit().apply {
            if (result != null) {
                putInt(KEY_FILES, result.files)
                putInt(KEY_WITH_TEXT, result.withText)
                putInt(KEY_FAILURES, result.failures)
                if (!result.stoppedEarly) putLong(KEY_LAST_RUN, System.currentTimeMillis())
            }
            putString(
                KEY_MESSAGE,
                error ?: when {
                    result?.unreadable == true -> "Couldn't read storage; the index was left as it was"
                    result?.capped == true -> "Stopped at the file limit"
                    else -> null
                },
            )
        }.apply()
        publish()
    }

    private fun schedule() {
        val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
        val request = PeriodicWorkRequestBuilder<FileIndexWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun publish() {
        val now = status
        listeners.forEach { it(now) }
    }

    companion object {
        private const val PERIODIC = "file-index-daily"
        private const val NOW = "file-index-now"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DECIDED = "decided"
        private const val KEY_FILES = "files"
        private const val KEY_WITH_TEXT = "with_text"
        private const val KEY_FAILURES = "failures"
        private const val KEY_LAST_RUN = "last_run"
        private const val KEY_MESSAGE = "message"

        @Volatile private var instance: ProFileIndex? = null

        fun get(context: Context): ProFileIndex =
            instance ?: synchronized(this) { instance ?: ProFileIndex(context.applicationContext).also { instance = it } }
    }
}
