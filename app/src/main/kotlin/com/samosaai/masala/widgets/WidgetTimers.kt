package com.samosaai.masala.widgets

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.timerComponents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.samosaai.masala.core.contract.resolved

/**
 * A widget timer that keeps time outside the home screen (plan
 * `docs/plans/widgets-functional-pass.md` §2.3). The card shows the countdown; the alarm is what
 * actually rings when the card is not composed, the screen is off, or the phone is in another app.
 *
 * The deadline lives in the widget's state (`<key>`), a paused timer keeps the millis left
 * (`@timer.<key>.left`), and `<key>.rang` records the deadline that already sounded so returning to
 * home never rings a stale alarm. When a timer names [WidgetComponent.Timer.autoStartKey], the next
 * one in the same widget starts at once — that is how a Pomodoro chains focus → break, and how the
 * hourly stand nudge repeats itself.
 */
object WidgetTimers {

    data class State(val deadline: Long?, val pausedLeftMs: Long?) {
        val running: Boolean get() = deadline != null && deadline > System.currentTimeMillis()
        val paused: Boolean get() = pausedLeftMs != null && pausedLeftMs > 0
    }

    fun leftKey(key: String) = "@timer.$key.left"
    fun rangKey(key: String) = "@timer.$key.rang"

    fun state(store: WidgetStateStore, widgetId: String, key: String): State {
        val values = store.values(widgetId)
        return State(
            deadline = values[key]?.toLongOrNull()?.takeIf { it > 0 },
            pausedLeftMs = values[leftKey(key)]?.toLongOrNull()?.takeIf { it > 0 },
        )
    }

    /** Milliseconds left to show: the live deadline, the paused remainder, or the full duration. */
    fun remainingMs(store: WidgetStateStore, widgetId: String, component: WidgetComponent.Timer): Long {
        val state = state(store, widgetId, component.key)
        return when {
            state.deadline != null -> (state.deadline - System.currentTimeMillis()).coerceAtLeast(0)
            state.paused -> state.pausedLeftMs!!
            else -> component.seconds * 1_000L
        }
    }

    fun start(context: Context, store: WidgetStateStore, widgetId: String, component: WidgetComponent.Timer) {
        val at = System.currentTimeMillis() + component.seconds * 1_000L
        // One map write: a deadline and a cleared remainder must land together.
        store.setAll(
            widgetId,
            store.values(widgetId) + (component.key to at.toString()) + (leftKey(component.key) to ""),
        )
        WidgetTimerScheduler.schedule(context, widgetId, component.key, at, component.sound)
    }

    /** Resume a paused timer, or start it if it is idle. */
    fun resume(context: Context, store: WidgetStateStore, widgetId: String, component: WidgetComponent.Timer) {
        val state = state(store, widgetId, component.key)
        val left = state.pausedLeftMs
        if (left == null) {
            start(context, store, widgetId, component)
            return
        }
        val at = System.currentTimeMillis() + left
        store.setAll(
            widgetId,
            store.values(widgetId) + (component.key to at.toString()) + (leftKey(component.key) to ""),
        )
        WidgetTimerScheduler.schedule(context, widgetId, component.key, at, component.sound)
    }

    fun pause(context: Context, store: WidgetStateStore, widgetId: String, component: WidgetComponent.Timer) {
        val state = state(store, widgetId, component.key)
        val deadline = state.deadline ?: return
        val left = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
        store.setAll(
            widgetId,
            store.values(widgetId) + (component.key to "") + (leftKey(component.key) to left.toString()),
        )
        WidgetTimerScheduler.cancel(context, widgetId, component.key)
    }

    fun stop(context: Context, store: WidgetStateStore, widgetId: String, component: WidgetComponent.Timer) {
        store.setAll(
            widgetId,
            store.values(widgetId) + (component.key to "") + (leftKey(component.key) to ""),
        )
        WidgetTimerScheduler.cancel(context, widgetId, component.key)
    }

    /**
     * The deadline passed. Rings once per deadline, clears the timer and starts the next phase when
     * the widget says so. [chain] is the widget's timers after preferences are applied.
     */
    fun fire(
        context: Context,
        store: WidgetStateStore,
        widgetId: String,
        key: String,
        sound: Boolean,
        chain: () -> List<WidgetComponent.Timer>,
    ) {
        val values = store.values(widgetId)
        val deadline = values[key]?.toLongOrNull() ?: return
        val alreadyRang = values[rangKey(key)] == deadline.toString()
        store.setAll(
            widgetId,
            values + (key to "") + (leftKey(key) to "") +
                if (alreadyRang) emptyMap() else mapOf(rangKey(key) to deadline.toString()),
        )
        if (alreadyRang) return
        if (sound) {
            // Home visible: the card's own chime. Away: a notification whose channel sound is the
            // alarm tone. If notifications are denied (Android 13+), ring here instead of dying
            // silently — that was the "Pomodoro finished with no sound" report.
            val visible = runCatching {
                (context.applicationContext as? MasalaApp)?.contextService?.homeVisible?.value
            }.getOrNull() == true
            val posted = !visible && WidgetTimerNotification.post(context, widgetId, key)
            if (visible || !posted) WidgetSounds.chime(context)
        }
        val timers = chain()
        val current = timers.firstOrNull { it.key == key } ?: return
        val next = current.autoStartKey?.let { nextKey -> timers.firstOrNull { it.key == nextKey } } ?: return
        start(context, store, widgetId, next)
    }

    /**
     * Re-arms every persisted deadline after a reboot, an app update or a clock change: an alarm
     * dies with the process, the deadline does not. Called from the boot receiver.
     */
    suspend fun rearmAll(context: Context) {
        val app = context.applicationContext as? MasalaApp ?: return
        val store = app.widgetStates
        store.loadAll()
        app.widgetLibrary.current().values.forEach { spec ->
            val resolved = spec.resolved(WidgetPrefsStore.of(store.values(spec.id)))
            resolved.timerComponents().forEach { timer ->
                val deadline = store.values(spec.id)[timer.key]?.toLongOrNull()?.takeIf { it > 0 }
                    ?: return@forEach
                WidgetTimerScheduler.schedule(context, spec.id, timer.key, deadline, timer.sound)
            }
        }
    }

}

/** Arms the one alarm that makes a widget timer ring away from home. */
object WidgetTimerScheduler {
    const val ACTION = "com.samosaai.masala.WIDGET_TIMER"
    private const val REQUEST_BASE = 71_000

    fun schedule(context: Context, widgetId: String, key: String, atMillis: Long, sound: Boolean) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = intent(context, widgetId, key, sound)
        // Exact is what a timer means; some phones deny it (Android 12+) — the inexact alarm still
        // fires, only later, and the visible card stays exact either way.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        runCatching {
            if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    fun cancel(context: Context, widgetId: String, key: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(intent(context, widgetId, key, sound = false)) }
    }

    private fun intent(context: Context, widgetId: String, key: String, sound: Boolean): PendingIntent {
        val intent = Intent(context, WidgetTimerReceiver::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_WIDGET, widgetId)
            .putExtra(EXTRA_KEY, key)
            .putExtra(EXTRA_SOUND, sound)
        return PendingIntent.getBroadcast(
            context,
            requestCode(widgetId, key),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(widgetId: String, key: String) = REQUEST_BASE + "$widgetId/$key".hashCode()

    const val EXTRA_WIDGET = "widget"
    const val EXTRA_KEY = "key"
    const val EXTRA_SOUND = "sound"
}

/** The alarm fired: ring, clear the deadline and chain the next phase. Runs with no UI. */
class WidgetTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetTimerScheduler.ACTION) return
        val widgetId = intent.getStringExtra(WidgetTimerScheduler.EXTRA_WIDGET) ?: return
        val key = intent.getStringExtra(WidgetTimerScheduler.EXTRA_KEY) ?: return
        val sound = intent.getBooleanExtra(WidgetTimerScheduler.EXTRA_SOUND, true)
        // A fire-and-forget receiver dies with onReceive. After the process was killed the widget's
        // state is not in memory either, so it has to be read from Room before the deadline exists.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? MasalaApp ?: return@launch
                val store = app.widgetStates
                store.load(listOf(widgetId))
                // The library is loaded asynchronously by the launcher; in a cold receiver process
                // it is still empty. Read this widget's spec straight from the database.
                val row = runCatching { app.database.widgets().get(widgetId) }.getOrNull() ?: return@launch
                val spec = runCatching {
                    com.samosaai.masala.core.contract.MasalaJson.strict
                        .decodeFromString(com.samosaai.masala.core.contract.WidgetSpec.serializer(), row.specJson)
                }.getOrNull() ?: return@launch
                val resolved = spec.resolved(WidgetPrefsStore.of(store.values(widgetId)))
                val chain = resolved.timerComponents()
                withContext(Dispatchers.Main) {
                    WidgetTimers.fire(context, store, widgetId, key, sound) { chain }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** "Focus finished": a notification that survives the screen being off, and opens home on tap. */
object WidgetTimerNotification {
    private const val CHANNEL = "widget_timers"
    private const val ID_BASE = 8_100

    /** True when a notification was actually posted; false when Android would not show it. */
    fun post(context: Context, widgetId: String, key: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            val audio = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.widget_timer_channel), NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), audio)
                        enableVibration(true)
                        description = context.getString(R.string.widget_timer_channel_description)
                    },
            )
        }
        val open = Intent(context, com.samosaai.masala.HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(
            context, ID_BASE + 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(R.string.widget_timer_done)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_masala)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.widget_timer_done_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        return runCatching { manager.notify(ID_BASE + widgetId.hashCode(), notification) }.isSuccess
    }
}
