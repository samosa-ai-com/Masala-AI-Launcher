package com.samosaai.masala.wallpapers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.wallpaper.RoutineEvaluator
import com.samosaai.masala.core.wallpaper.WallpaperRoutine
import com.samosaai.masala.tools.WallpaperTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.LocalDateTime

/**
 * Applies wallpaper routines (plan `docs/plans/wallpaper-routines.md` §3).
 *
 * Four triggers, all cheap and none of them a poll: app start / a return to home, a context
 * snapshot change (debounced), the next time-window boundary (an in-process timer while Masala is
 * alive), and the unlock broadcast. Applying is skipped when the same rule already won, so an
 * unlock never repaints the wallpaper.
 *
 * Weather is whatever the context service already has: a routine never fetches, so a failed
 * weather update simply means weather rules do not match — the time rules still work.
 */
class WallpaperRoutineEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: WallpaperRoutineStore,
    private val wallpapers: WallpaperStore,
    private val tool: WallpaperTool,
    private val snapshot: StateFlow<ContextSnapshot>,
) {
    /** What the settings screen shows: which rule won, and when the next change is due. */
    data class Status(
        val routineName: String? = null,
        val ruleIndex: Int? = null,
        val source: String? = null,
        val nextChange: LocalDateTime? = null,
        val error: String? = null,
        val appliedAt: Long = 0L,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val applyLock = Mutex()
    private val prefs = context.getSharedPreferences("wallpaper_routines", Context.MODE_PRIVATE)
    private var contextJob: Job? = null
    private var timerJob: Job? = null
    private var receiver: BroadcastReceiver? = null

    @OptIn(FlowPreview::class)
    fun start() {
        if (contextJob != null) return
        contextJob = scope.launch {
            // A snapshot lands every minute; one evaluation a minute is plenty, and the debounce
            // merges the bursts that a battery or notification update produces.
            snapshot.drop(1).debounce(30_000).collect { apply("context") }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) refresh("unlock")
            }
        }.also {
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    it,
                    IntentFilter(Intent.ACTION_USER_PRESENT),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        }
        refresh("start")
    }

    /** Evaluates now (a save, a return to home, an unlock). Safe to call from any thread. */
    fun refresh(reason: String = "manual") {
        scope.launch { apply(reason) }
    }

    private suspend fun apply(reason: String) = applyLock.withLock {
        val routines = runCatching { store.all() }.getOrElse { return@withLock }
        scheduleNext(routines)
        val match = RoutineEvaluator.choose(routines, snapshot.value)
        if (match == null) {
            _status.value = _status.value.copy(error = null, nextChange = nextChange(routines))
            return@withLock
        }
        val key = "${match.routineId}|${match.ruleIndex}|${match.source}"
        val name = routines.firstOrNull { it.id == match.routineId }?.name
        if (prefs.getString(KEY_APPLIED, null) == key) {
            // The same rule still wins: leave the wallpaper alone (no flicker on every unlock).
            _status.value = Status(
                routineName = name,
                ruleIndex = match.ruleIndex,
                source = match.source,
                nextChange = nextChange(routines),
                appliedAt = prefs.getLong(KEY_AT, 0L),
            )
            return@withLock
        }
        val file = WallpaperSources.resolve(context, match.source) { id ->
            runCatching { wallpapers.byId(id)?.file }.getOrNull()
        }
        if (file == null || !file.canRead()) {
            _status.value = Status(
                routineName = name,
                ruleIndex = match.ruleIndex,
                source = match.source,
                nextChange = nextChange(routines),
                error = "'${match.source}' isn't available on this phone",
            )
            return@withLock
        }
        val result = runCatching { tool.setWallpaper(file.absolutePath, "home") }
            .getOrElse { ActionResult.error(it.message ?: "Couldn't set the wallpaper") }
        if (!result.success) {
            _status.value = Status(
                routineName = name,
                ruleIndex = match.ruleIndex,
                source = match.source,
                nextChange = nextChange(routines),
                error = result.message,
            )
            return@withLock
        }
        val now = System.currentTimeMillis()
        prefs.edit().putString(KEY_APPLIED, key).putLong(KEY_AT, now).apply()
        _status.value = Status(
            routineName = name,
            ruleIndex = match.ruleIndex,
            source = match.source,
            nextChange = nextChange(routines),
            appliedAt = now,
        )
    }

    /** Re-arms the in-process boundary timer; a minute of slack keeps it on the safe side. */
    private fun scheduleNext(routines: List<WallpaperRoutine>) {
        timerJob?.cancel()
        val next = RoutineEvaluator.nextBoundary(routines, LocalDateTime.now()) ?: return
        val waitMs = Duration.between(LocalDateTime.now(), next).toMillis().coerceIn(5_000L, MAX_WAIT_MS)
        timerJob = scope.launch {
            delay(waitMs + 5_000L)
            apply("boundary")
        }
    }

    private fun nextChange(routines: List<WallpaperRoutine>): LocalDateTime? =
        RoutineEvaluator.nextBoundary(routines, LocalDateTime.now())

    /** Remembers one applied rule so a restart doesn't repaint the same wallpaper. */
    fun forgetApplied() {
        prefs.edit().remove(KEY_APPLIED).apply()
    }

    private companion object {
        const val KEY_APPLIED = "applied"
        const val KEY_AT = "appliedAt"

        /** Six hours: a long gap still re-checks, so a clock change can't park the timer forever. */
        const val MAX_WAIT_MS = 6 * 60 * 60 * 1000L
    }
}
