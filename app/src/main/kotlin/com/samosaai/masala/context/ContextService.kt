package com.samosaai.masala.context

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.samosaai.masala.core.contract.ContextOverride
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.NextEvent
import com.samosaai.masala.layout.ModeStore
import com.samosaai.masala.notifications.MasalaNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Application-wide [ContextSnapshot] (arch §3.4, plan Phase 2): the clock to the minute, the next
 * calendar event, battery and charging, Bluetooth devices, notification counts and the active modes.
 * Runtime receivers, because the home app's process is rarely killed; a minute tick runs only while
 * home is visible ([setHomeVisible]). Signals the phone can't give (no permission) are null or empty,
 * which the rules treat as false. Health, sleep, steps and weather are Phase 6.
 *
 * [override] is the debug panel: any field it sets replaces the real one, and the result is what rules
 * and the renderer see. Only background work (DND, modes, reminders, scheduled skills) sets alarms; this
 * service never does.
 */
class ContextService(
    private val context: Context,
    private val scope: CoroutineScope,
    modes: ModeStore,
    /** Open-Meteo, the same client the brief uses; null keeps weather out (tests). */
    private val weatherClient: com.samosaai.masala.weather.WeatherClient? = null,
    /** Where the saved place lives (Settings → Daily brief). */
    private val settings: com.samosaai.masala.data.SettingsStore? = null,
) {
    private data class Real(
        val battery: Pair<Boolean?, Int?> = null to null,
        val bluetooth: List<String> = emptyList(),
        val nextEvent: NextEvent? = null,
        val nextEvents: List<NextEvent> = emptyList(),
        val weather: com.samosaai.masala.core.contract.WeatherNow? = null,
        val steps: Int? = null,
        val sleepHours: Double? = null,
        val stepsAccess: Boolean? = null,
        val sleepAccess: Boolean? = null,
        val calendarAccess: Boolean? = null,
    )

    private val real = MutableStateFlow(Real())
    private val minute = MutableStateFlow(minuteNow())
    private val overrideState = MutableStateFlow(ContextOverride())

    /** Whether home is the visible screen (Resume/Pause). A finished turn notifies only when this is false. */
    val homeVisible = MutableStateFlow(false)

    /** What the debug panel set; empty in normal use. */
    val override: StateFlow<ContextOverride> = overrideState.asStateFlow()

    /** The snapshot rules run on. Changes only when a signal does, so it can drive recomposition directly. */
    val snapshot: StateFlow<ContextSnapshot> = combine(
        minute,
        real,
        MasalaNotificationListener.urgent,
        modes.active,
        overrideState,
    ) { now, real, urgent, active, override ->
        override.apply(
            ContextSnapshot(
                now = now,
                nextEvent = real.nextEvent,
                nextEvents = real.nextEvents,
                charging = real.battery.first,
                batteryPercent = real.battery.second,
                bluetoothDevices = real.bluetooth,
                activeModes = active.sorted(),
                urgentCount = urgent,
                weather = real.weather,
                steps = real.steps,
                sleepHours = real.sleepHours,
                stepsAccess = real.stepsAccess,
                sleepAccess = real.sleepAccess,
                calendarAccess = real.calendarAccess,
            ),
        )
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, overrideState.value.initial())

    private fun ContextOverride.initial() = apply(ContextSnapshot(now = minuteNow()))

    private val connected = linkedMapOf<String, String>() // address -> name
    private var tick: kotlinx.coroutines.Job? = null
    private var started = false

    fun setOverride(value: ContextOverride) {
        overrideState.value = value
    }

    fun start() {
        if (started) return
        started = true
        registerBattery()
        registerBluetooth()
        startWeather()
        startHealth()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refreshCalendar()
        }
        runCatching { context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer) }
        refreshCalendar()
    }

    /** The minute tick runs only while home shows (plan Phase 2); coming back also refreshes at once. */
    fun setHomeVisible(visible: Boolean) {
        homeVisible.value = visible
        tick?.cancel()
        tick = null
        if (!visible) return
        minute.value = minuteNow()
        refreshCalendar()
        // A phone that was away for a while (or had no network) gets a fresh reading on return.
        scope.launch { refreshWeather() }
        scope.launch { refreshHealth() }
        tick = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                delay(60_000 - now % 60_000 + 50)
                minute.value = minuteNow()
                // A new minute can move the next event.
                refreshCalendar()
            }
        }
    }

    private fun minuteNow(): LocalDateTime = LocalDateTime.now().withSecond(0).withNano(0)

    // ---- battery ----

    private fun registerBattery() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = updateBattery(intent)
        }
        // The sticky intent gives the first value at once.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let(::updateBattery)
    }

    private fun updateBattery(intent: Intent) {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        real.value = real.value.copy(battery = (if (status == -1) null else charging) to percent)
    }

    // ---- bluetooth ----

    private fun hasBluetooth() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // Every read of a device name is behind hasBluetooth() and a runCatching.
    @SuppressLint("MissingPermission")
    private fun registerBluetooth() {
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(c: Context, intent: Intent) {
                if (!hasBluetooth()) return
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                runCatching {
                    if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) connected[device.address] = device.name ?: device.address
                    else connected.remove(device.address)
                }
                publishBluetooth()
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        // Devices already connected when Masala started.
        if (hasBluetooth()) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
                runCatching {
                    adapter.getProfileProxy(
                        context,
                        object : BluetoothProfile.ServiceListener {
                            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                                runCatching { proxy.connectedDevices.forEach { connected[it.address] = it.name ?: it.address } }
                                publishBluetooth()
                                adapter.closeProfileProxy(p, proxy)
                            }

                            override fun onServiceDisconnected(p: Int) = Unit
                        },
                        profile,
                    )
                }.onFailure { Log.w(TAG, "No Bluetooth profile $profile", it) }
            }
        }
    }

    private fun publishBluetooth() {
        real.value = real.value.copy(bluetooth = connected.values.toList())
    }

    // ---- weather ----

    private var weatherJob: kotlinx.coroutines.Job? = null

    /**
     * Today's weather for the widgets (`rain_soon`, `weather_globe`). The place is the one saved in
     * Settings → Daily brief; without one, the phone's last known coarse location is used when the
     * user has allowed location. Refreshed every 20 minutes (the client caches for 30, so this is
     * one request per half hour at most) and whenever home comes back.
     */
    private fun startWeather() {
        if (weatherClient == null || weatherJob != null) return
        weatherJob = scope.launch {
            while (isActive) {
                refreshWeather()
                delay(20 * 60_000L)
            }
        }
    }

    private suspend fun refreshWeather() {
        val client = weatherClient ?: return
        val place = runCatching { settings?.settings?.firstOrNull()?.briefPlace }.getOrNull()
            ?: runCatching {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    withContext(Dispatchers.IO) {
                        com.samosaai.masala.weather.WeatherClient.currentPlace(context, "Here")
                    }
                } else {
                    null
                }
            }.getOrNull()
        if (place == null) {
            real.value = real.value.copy(weather = null)
            return
        }
        // today() returns a Result of its own; only a success that carried a temperature counts.
        // A transient failure keeps the last reading: flashing "set a place" on a weak connection
        // looked like the widget had lost its configuration.
        runCatching { client.today(place).getOrNull() }
            .onSuccess { daily ->
                daily?.now?.let { now -> real.value = real.value.copy(weather = now) }
            }
            .onFailure { Log.w(TAG, "Weather refresh failed", it) }
    }

    // ---- health ----

    private var healthJob: kotlinx.coroutines.Job? = null

    /** Steps and last night's sleep for the health widgets, when Health Connect is granted. */
    private fun startHealth() {
        if (healthJob != null) return
        healthJob = scope.launch {
            while (isActive) {
                if (homeVisible.value) refreshHealth()
                delay(15 * 60_000L)
            }
        }
    }

    private suspend fun refreshHealth() {
        val reading = runCatching { com.samosaai.masala.health.HealthSignals.read(context) }.getOrNull() ?: return
        real.value = real.value.copy(
            steps = reading.steps,
            sleepHours = reading.sleepHours,
            stepsAccess = reading.stepsAccess,
            sleepAccess = reading.sleepAccess,
        )
    }

    // ---- calendar ----

    private fun refreshCalendar() {
        scope.launch {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
            val events = if (granted) withContext(Dispatchers.IO) { queryNextEvents() } else emptyList()
            real.value = real.value.copy(
                nextEvent = events.firstOrNull(),
                nextEvents = events,
                calendarAccess = granted,
            )
        }
    }

    /** The next few events in the coming day; the first is what rules see as [Real.nextEvent]. */
    private fun queryNextEvents(limit: Int = 5): List<NextEvent> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now)
            android.content.ContentUris.appendId(it, now + 24 * 3_600_000L)
        }.build()
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                val events = mutableListOf<NextEvent>()
                while (c.moveToNext() && events.size < limit) {
                    val begin = c.getLong(1)
                    // Already started and all-day events aren't "next".
                    if (begin < now || c.getInt(3) == 1) continue
                    events += NextEvent(
                        title = c.getString(0).orEmpty().ifBlank { "Event" },
                        start = LocalDateTime.ofInstant(Instant.ofEpochMilli(begin), ZoneId.systemDefault()),
                        location = c.getString(2)?.takeIf { it.isNotBlank() },
                    )
                }
                events
            }.orEmpty()
        }.onFailure { Log.w(TAG, "Calendar query failed", it) }.getOrDefault(emptyList())
    }

    private companion object {
        const val TAG = "ContextService"
    }
}
