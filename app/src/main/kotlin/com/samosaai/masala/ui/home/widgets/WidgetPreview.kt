package com.samosaai.masala.ui.home.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.samosaai.masala.core.contract.BindingSource
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.NextEvent
import com.samosaai.masala.core.contract.WeatherKind
import com.samosaai.masala.core.contract.WeatherNow
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.masala
import com.samosaai.masala.widgets.WidgetStateStore
import java.time.LocalDateTime

/**
 * A still of a widget, for the gallery and the manager.
 *
 * It renders the widget's own spec — never a screenshot — so a preview can't drift from what gets
 * placed. Values are samples: a half-filled counter, a partly ticked list, realistic context. No
 * polling, no live streams, no web fetches: a gallery of thirty cards stays cheap, and nothing on
 * this screen can write to the widget's real state.
 */
@Composable
fun WidgetPreview(
    spec: WidgetSpec,
    modifier: Modifier = Modifier,
    rows: Int = spec.size.default,
    /** False for a long list: a globe otherwise keeps drawing below the fold. */
    animate: Boolean = true,
    /** The widget's real share of the grid (2 of 4 cells = 0.5), so a small tile looks small. */
    widthFraction: Float = 1f,
) {
    val app = LocalContext.current.masala
    val scope = rememberCoroutineScope()
    // A private, in-memory store per spec: taps in a preview change the sample, never the widget.
    val store = remember(spec.id) {
        WidgetStateStore(scope, dao = null).apply {
            sampleValues(spec).forEach { (key, value) -> set(spec.id, key, value) }
        }
    }
    val source = remember(spec.id, store) {
        WidgetSource(
            snapshot = { SAMPLE_SNAPSHOT },
            store = store,
            onAction = {},
            extra = { (app.brainFacts() + app.meetingFacts()).ifEmpty { SAMPLE_EXTRA } },
            animate = animate,
        )
    }
    Box(
        modifier.fillMaxWidth(widthFraction.coerceIn(0.25f, 1f))
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(8.dp),
    ) {
        WidgetCard(spec, rows = rows, source = source)
    }
}

/** A Tuesday afternoon with a little of everything, so no preview is a wall of dashes. */
private val SAMPLE_SNAPSHOT = ContextSnapshot(
    // Whole minutes: `LocalTime.toString()` prints seconds and nanos, and a preview reading
    // "15:00:02.266401" looked like a bug (it was one).
    now = LocalDateTime.now().withHour(14).withMinute(20).withSecond(0).withNano(0),
    nextEvent = NextEvent(
        "Design review",
        LocalDateTime.now().withHour(15).withMinute(0).withSecond(0).withNano(0),
        "Studio",
    ),
    charging = false,
    batteryPercent = 72,
    activeModes = emptyList(),
    urgentCount = 2,
    sleepHours = 7.4,
    steps = 6_120,
    weather = WeatherNow(WeatherKind.CLEAR, 24.0),
)

/** Stand-ins for values only the app can read, for a preview built before the app has data. */
private val SAMPLE_EXTRA = mapOf("notes" to "42", "latest" to "Ideas from the train ride")

/**
 * Believable numbers for the components that keep their own state: a counter two-thirds done, a
 * list with its first items ticked, a seven-day chart. Timers stay at their full length (started
 * would run down while the gallery is open).
 */
internal fun sampleValues(spec: WidgetSpec): Map<String, String> {
    val values = linkedMapOf<String, String>()
    fun walk(component: WidgetComponent) {
        when (component) {
            is WidgetComponent.Counter -> {
                val target = component.goal?.let { (it * 2 / 3).coerceAtLeast(1) } ?: 3
                values[component.key] = target.toString()
            }
            is WidgetComponent.Checklist -> {
                val half = (component.items.size + 1) / 2
                values[component.key] = component.items.indices.joinToString(",") { if (it < half) "1" else "0" }
            }
            is WidgetComponent.Chart -> storageKey(component.binding)?.let { values[it] = "3,5,4,7,6,8,4" }
            is WidgetComponent.Progress -> storageKey(component.binding)?.let { values[it] = "640" }
            is WidgetComponent.Gauge -> storageKey(component.binding)?.let { values[it] = "6" }
            is WidgetComponent.Text -> component.binding?.let(::storageKey)?.let { values[it] = "Sample" }
            is WidgetComponent.ListRows -> storageKey(component.binding)?.let { values[it] = "First thing\nSecond thing" }
            is WidgetComponent.Row -> component.children.forEach(::walk)
            is WidgetComponent.Column -> component.children.forEach(::walk)
            else -> Unit
        }
    }
    spec.components.forEach(::walk)
    return values
}

private fun storageKey(binding: com.samosaai.masala.core.contract.Binding): String? =
    binding.key.takeIf { binding.source == BindingSource.STORAGE && !it.isNullOrBlank() }
