package com.samosaai.masala.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.core.contract.AddItem
import com.samosaai.masala.core.contract.Condition
import com.samosaai.masala.core.contract.ContextOverride
import com.samosaai.masala.core.contract.ContextOverride.Override
import com.samosaai.masala.core.contract.Effect
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.ListSection
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.ModePatch
import com.samosaai.masala.core.contract.Rule
import com.samosaai.masala.core.contract.WeatherKind
import com.samosaai.masala.core.contract.WeatherNow
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.masala
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Debug builds only (plan Phase 2): override any field of the [com.samosaai.masala.core.contract.ContextSnapshot]
 * and watch home show and hide items live. Each field is "real" (leave it alone) or a value; `none`
 * pretends the signal isn't there. A button loads a small layout whose items each depend on one signal.
 */
class ContextPanelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) { Surface(Modifier.fillMaxSize()) { Panel() } }
        }
    }

    private enum class Tri { REAL, ON, OFF, NONE }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Panel() {
        val app = masala
        val scope = rememberCoroutineScope()
        val snapshot by app.contextService.snapshot.collectAsStateWithLifecycle()
        val current = remember { app.contextService.override.value }

        var time by remember { mutableStateOf(current.now?.toString().orEmpty()) }
        var charging by remember { mutableStateOf(when { current.charging == null -> Tri.REAL; current.charging?.value == null -> Tri.NONE; current.charging?.value == true -> Tri.ON; else -> Tri.OFF }) }
        var battery by remember { mutableStateOf(current.batteryPercent?.value?.toString().orEmpty()) }
        var bluetooth by remember { mutableStateOf(current.bluetoothDevices?.joinToString(",").orEmpty()) }
        var modes by remember { mutableStateOf(current.activeModes?.joinToString(",").orEmpty()) }
        var urgent by remember { mutableStateOf(current.urgentCount?.toString().orEmpty()) }
        var nextEvent by remember { mutableStateOf(current.minutesToNextEvent?.let { it.value?.toString() ?: "none" }.orEmpty()) }
        var sleep by remember { mutableStateOf(current.sleepHours?.value?.toString().orEmpty()) }
        var steps by remember { mutableStateOf(current.steps?.value?.toString().orEmpty()) }
        var weather by remember { mutableStateOf(current.weather?.value?.let { "${it.kind.name.lowercase()},${it.tempC}" }.orEmpty()) }
        var message by remember { mutableStateOf("") }

        fun apply() {
            val build = runCatching {
                ContextOverride(
                    now = time.takeIf { it.isNotBlank() }?.let { text ->
                        // "HH:mm" means today at that time.
                        if (text.length <= 5) LocalDateTime.of(java.time.LocalDate.now(), LocalTime.parse(text)) else LocalDateTime.parse(text)
                    },
                    charging = when (charging) { Tri.REAL -> null; Tri.ON -> Override(true); Tri.OFF -> Override(false); Tri.NONE -> Override(null) },
                    batteryPercent = battery.toIntOrNull()?.let { Override(it) },
                    bluetoothDevices = bluetooth.takeIf { it.isNotBlank() }?.split(",")?.map(String::trim)?.filter(String::isNotEmpty),
                    activeModes = modes.takeIf { it.isNotBlank() }?.split(",")?.map(String::trim)?.filter(String::isNotEmpty),
                    urgentCount = urgent.toIntOrNull(),
                    minutesToNextEvent = when { nextEvent == "none" -> Override(null); nextEvent.isBlank() -> null; else -> Override(nextEvent.toInt()) },
                    sleepHours = sleep.toDoubleOrNull()?.let { Override(it) },
                    steps = steps.toIntOrNull()?.let { Override(it) },
                    weather = weather.takeIf { it.isNotBlank() }?.split(",")?.let { (kind, temp) ->
                        Override(WeatherNow(WeatherKind.valueOf(kind.trim().uppercase()), temp.trim().toDouble()))
                    },
                )
            }
            build.onSuccess { app.contextService.setOverride(it); message = "Applied" }
                .onFailure { message = "Can't read that: ${it.message}" }
        }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Context panel", style = MaterialTheme.typography.titleLarge)
            Text(
                "Now: ${snapshot.now} · charging=${snapshot.charging} · battery=${snapshot.batteryPercent} · modes=${snapshot.activeModes} · urgent=${snapshot.urgentCount} · event=${snapshot.nextEvent?.start} · bt=${snapshot.bluetoothDevices}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("context_now"),
            )
            Field("Time (HH:mm or yyyy-MM-ddTHH:mm; empty = real)", time, "ctx_time") { time = it }
            Text("Charging")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tri.entries.forEach { t -> FilterChip(selected = charging == t, onClick = { charging = t }, label = { Text(t.name.lowercase()) }) }
            }
            Field("Battery %", battery, "ctx_battery") { battery = it }
            Field("Bluetooth devices (comma separated)", bluetooth, "ctx_bluetooth") { bluetooth = it }
            Field("Active modes (comma separated)", modes, "ctx_modes") { modes = it }
            Field("Urgent notifications", urgent, "ctx_urgent") { urgent = it }
            Field("Minutes to next event (number, or none)", nextEvent, "ctx_event") { nextEvent = it }
            Field("Sleep hours", sleep, "ctx_sleep") { sleep = it }
            Field("Steps", steps, "ctx_steps") { steps = it }
            Field("Weather (kind,tempC e.g. rain,7)", weather, "ctx_weather") { weather = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::apply, modifier = Modifier.testTag("ctx_apply")) { Text("Apply") }
                OutlinedButton(onClick = {
                    time = ""; charging = Tri.REAL; battery = ""; bluetooth = ""; modes = ""; urgent = ""; nextEvent = ""; sleep = ""; steps = ""; weather = ""
                    app.contextService.setOverride(ContextOverride()); message = "Back to the real signals"
                }, modifier = Modifier.testTag("ctx_reset")) { Text("Reset") }
            }
            OutlinedButton(
                onClick = { scope.launch { message = loadRuleDemo() } },
                modifier = Modifier.testTag("ctx_demo").fillMaxWidth(),
            ) { Text("Load rules demo layout") }
            if (message.isNotEmpty()) Text(message, modifier = Modifier.testTag("ctx_message"))
        }
    }

    @Composable
    private fun Field(label: String, value: String, tag: String, onChange: (String) -> Unit) {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(tag))
    }

    /** Adds a list whose items each show on one signal, plus a mode and two actions that ask first. */
    private suspend fun loadRuleDemo(): String {
        val layouts = masala.layouts
        val base = layouts.base.value ?: return "Layout not loaded yet"
        val hideUnless = { c: Condition -> listOf(Rule(Condition.Not(c), Effect.Hide)) }
        fun item(id: String, label: String, symbol: String, action: MasalaAction = MasalaAction.OpenDrawer, rules: List<Rule> = emptyList()) =
            Item(id, label, IconRef.Symbol(symbol), action, rules)
        val demo = ListSection(
            id = "demo",
            title = "Rules demo",
            items = listOf(
                item("demo-charging", "Charging now", "battery_full", rules = hideUnless(Condition.Charging())),
                item("demo-night", "Night (22:00–07:00)", "bedtime", rules = hideUnless(Condition.TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0)))),
                item("demo-meeting", "Meeting in 30 min", "event", rules = hideUnless(Condition.MinutesToNextEvent(maxMinutes = 30))),
                item("demo-urgent", "Urgent notifications", "priority_high", rules = hideUnless(Condition.UrgentCount())),
                item("demo-focus", "Focus mode is on", "star", rules = hideUnless(Condition.ModeActive("focus"))),
                item("demo-toggle", "Turn on Focus mode", "tune", MasalaAction.SetMode("focus", true)),
                item("demo-alarm", "Alarm 07:30", "alarm", MasalaAction.SetAlarm(7, 30, "Demo")),
                item("demo-timer", "Timer 1 min", "timer", MasalaAction.StartTimer(60)),
            ),
        )
        masala.modes.put(ModePatch(id = "focus", name = "Focus"))
        val pages = base.pages.mapIndexed { i, page ->
            if (i != 0) page else page.copy(sections = page.sections.filterNot { it.id == "demo" }.let { list ->
                val dockAt = list.indexOfFirst { it is com.samosaai.masala.core.contract.DockSection }.let { if (it < 0) list.size else it }
                list.take(dockAt) + demo + list.drop(dockAt)
            })
        }
        val problems = layouts.apply("Rules demo", base.copy(pages = pages))
        return if (problems.isEmpty()) "Rules demo loaded" else problems.joinToString("\n")
    }
}
