package com.samosaai.masala.ui.home.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.Align
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.Emphasis
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.contract.WidgetStyle
import com.samosaai.masala.core.widgets.WidgetValues
import com.samosaai.masala.ui.home.SymbolIcons
import com.samosaai.masala.ui.home.WallpaperCard
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.theme.OnWallpaper
import com.samosaai.masala.widgets.HttpValueStore
import com.samosaai.masala.widgets.WidgetStateStore
import com.samosaai.masala.widgets.StreamSourceStore
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.delay

/**
 * What a generated widget needs from home (plan `docs/plans/widget-maker.md` W1): the current
 * snapshot for context bindings, the widget's own stored values, and where actions go. The
 * renderer never touches the library: the layout already carries the spec inline.
 */
data class WidgetSource(
    val snapshot: () -> ContextSnapshot,
    val store: WidgetStateStore,
    val onAction: (MasalaAction) -> Unit,
    val onLongPress: (() -> Unit)? = null,
    /** Stream sources for video/snapshot; media without it keeps the W4 note. */
    val streams: StreamSourceStore? = null,
    /** The player pool and fullscreen tap for live video; without it video stays a note. */
    val media: com.samosaai.masala.widgets.media.MediaHost? = null,
    /** Fetched web values; web_value without it keeps the W5 note. */
    val http: HttpValueStore? = null,
)

/** One generated widget on home: a glass card with its components top to bottom. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetCard(spec: WidgetSpec, rows: Int?, source: WidgetSource, modifier: Modifier = Modifier) {
    val snapshot = source.snapshot()
    val states by source.store.states.collectAsState()
    // New values (a counter tap, a checklist tick) recompose this card alone.
    LaunchedEffect(spec.id) { source.store.load(listOf(spec.id)) }
    val storage = states[spec.id].orEmpty()
    val longPress = source.onLongPress
    WallpaperCard(
        modifier.fillMaxWidth().heightIn(min = ((rows ?: spec.size.default) * 48).dp)
            .then(if (longPress != null) Modifier.combinedClickable(onClick = {}, onLongClick = longPress) else Modifier),
    ) {
        spec.title?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = OnWallpaper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
        }
        spec.components.forEach { component ->
            Component(component, spec.id, storage, snapshot, source, depth = 0)
        }
    }
}

@Composable
private fun Component(
    component: WidgetComponent,
    widgetId: String,
    storage: Map<String, String>,
    snapshot: ContextSnapshot,
    source: WidgetSource,
    depth: Int,
) {
    when (component) {
        is WidgetComponent.Text -> {
            val text = component.text ?: component.binding?.let { WidgetValues.textOf(it, storage, snapshot) } ?: return
            Text(
                text,
                style = if (component.style?.emphasis == Emphasis.LARGE) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                color = tinted(component.style),
                textAlign = alignOf(component.style),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is WidgetComponent.Counter -> {
            val value = source.store.int(widgetId, component.key)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(component.label, style = MaterialTheme.typography.labelLarge, color = MasalaTheme.tokens.onWallpaperMedium)
                    Text(
                        if (component.goal != null) "$value / ${component.goal}" else value.toString(),
                        style = if (component.style?.emphasis == Emphasis.LARGE) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                        color = tinted(component.style),
                    )
                    component.goal?.let { goal ->
                        LinearProgressIndicator(
                            progress = { (value.toFloat() / goal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
                Button(onClick = { source.store.increment(widgetId, component.key, component.step) }) { Text("+${component.step}") }
            }
        }
        is WidgetComponent.Checklist -> {
            val ticks = source.store.bools(widgetId, component.key, component.items.size)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                component.items.forEachIndexed { i, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { source.store.toggle(widgetId, component.key, i, component.items.size) },
                    ) {
                        Checkbox(checked = ticks.getOrElse(i) { false }, onCheckedChange = { source.store.toggle(widgetId, component.key, i, component.items.size) })
                        Text(item, style = MaterialTheme.typography.bodyMedium, color = if (ticks.getOrElse(i) { false }) MasalaTheme.tokens.onWallpaperMedium else OnWallpaper)
                    }
                }
            }
        }
        is WidgetComponent.Timer -> TimerRow(component, widgetId, storage, source)
        is WidgetComponent.Progress -> {
            val value = component.binding.let { WidgetValues.numberOf(it, storage, snapshot) }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(component.label, style = MaterialTheme.typography.labelLarge, color = MasalaTheme.tokens.onWallpaperMedium)
                    Text(value?.let { trim(it) } ?: "—", style = MaterialTheme.typography.labelLarge, color = tinted(component.style))
                }
                LinearProgressIndicator(
                    progress = { value?.let { (it / component.max).toFloat().coerceIn(0f, 1f) } ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        is WidgetComponent.Chart -> ChartBars(component, storage, snapshot)
        is WidgetComponent.Button -> {
            Button(onClick = { source.onAction(component.action) }, modifier = Modifier.fillMaxWidth()) { Text(component.label) }
        }
        is WidgetComponent.Image -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                com.samosaai.masala.ui.home.SymbolDisc(SymbolIcons.icon((component.icon as? com.samosaai.masala.core.contract.IconRef.Symbol)?.name ?: "star"), null, 40)
            }
        }
        is WidgetComponent.ListRows -> {
            val rows = component.binding.let { WidgetValues.rowsOf(it, storage, snapshot, component.maxRows) }
            if (rows.isEmpty()) {
                Text("Nothing here yet", style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rows.forEach { row ->
                        Text(
                            row,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnWallpaper,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                                .then(component.rowAction?.let { action -> Modifier.clickable(role = Role.Button) { source.onAction(action) } } ?: Modifier),
                        )
                    }
                }
            }
        }
        is WidgetComponent.Gauge -> {
            val value = component.binding.let { WidgetValues.numberOf(it, storage, snapshot) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GaugeRing(value, component.max, tinted(component.style))
                Column {
                    Text(component.label, style = MaterialTheme.typography.labelLarge, color = MasalaTheme.tokens.onWallpaperMedium)
                    Text(value?.let { trim(it) } ?: "—", style = MaterialTheme.typography.titleLarge, color = tinted(component.style))
                }
            }
        }
        is WidgetComponent.Row -> {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                component.children.forEach { child ->
                    Column(Modifier.weight(1f)) { Component(child, widgetId, storage, snapshot, source, depth + 1) }
                }
            }
        }
        is WidgetComponent.Column -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                component.children.forEach { child -> Component(child, widgetId, storage, snapshot, source, depth + 1) }
            }
        }
        // Live media (W4): MJPEG and snapshots draw; player kinds need the pool.
        is WidgetComponent.Video -> {
            val src = source.streams?.current()?.get(component.stream.sourceId)
            val media = source.media
            when {
                src == null -> MediaNote(stringResource(R.string.widgets_video_no_source))
                src.kind == com.samosaai.masala.widgets.StreamKind.MJPEG && source.streams != null ->
                    com.samosaai.masala.widgets.media.MjpegImage(src.id, source.streams)
                media != null && source.streams != null ->
                    com.samosaai.masala.widgets.media.LiveVideo(src.id, source.streams, media)
                else -> MediaNote(stringResource(R.string.widgets_video_player_next))
            }
        }
        is WidgetComponent.Snapshot -> {
            val src = source.streams?.current()?.get(component.sourceId)
            if (src == null || source.streams == null) MediaNote(stringResource(R.string.widgets_video_no_source))
            else com.samosaai.masala.widgets.media.SnapshotImage(src.id, component.everySeconds, source.streams)
        }
        is WidgetComponent.WebValue -> {
            val store = source.http
            if (store == null) {
                Text(
                    component.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MasalaTheme.tokens.onWallpaperMedium,
                )
                Text(
                    (component.suffix?.let { "— $it" } ?: "—") + " · web value in W5",
                    style = MaterialTheme.typography.titleLarge,
                    color = OnWallpaper,
                )
            } else {
                com.samosaai.masala.widgets.media.WebValueView(component, widgetId, component.suffix, store, component.style)
            }
        }
    }
}

@Composable
private fun MediaNote(note: String) {
    Text(note, style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun TimerRow(component: WidgetComponent.Timer, widgetId: String, storage: Map<String, String>, source: WidgetSource) {
    val deadline = storage[component.key]?.toLongOrNull()
    val now = System.currentTimeMillis()
    val left = if (deadline == null) null else (deadline - now).coerceAtLeast(0)
    // While running, the line ticks once a second; a finished timer goes quiet.
    if (left != null && left > 0) {
        LaunchedEffect(deadline) {
            delay(left.coerceAtMost(3_600_000L))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(component.label, style = MaterialTheme.typography.labelLarge, color = MasalaTheme.tokens.onWallpaperMedium)
        Text(
            left?.let { formatDuration(it) } ?: formatDuration(component.seconds * 1000L),
            style = MaterialTheme.typography.titleLarge,
            color = OnWallpaper,
        )
        if (left == null || left <= 0) {
            TextButton(onClick = { source.store.set(widgetId, component.key, (System.currentTimeMillis() + component.seconds * 1000L).toString()) }) {
                Text("Start")
            }
        } else {
            TextButton(onClick = { source.store.set(widgetId, component.key, "0") }) { Text("Reset") }
        }
    }
}

@Composable
private fun ChartBars(component: WidgetComponent.Chart, storage: Map<String, String>, snapshot: ContextSnapshot) {
    // v1: storage holds comma-separated values, oldest first; without data the card says so.
    val values = storage[component.binding.key]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeLast(component.days).orEmpty()
    if (values.isEmpty()) {
        Text("No data yet", style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium)
        return
    }
    val max = (values.maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
    val accent = MasalaTheme.tokens.accent
    Row(
        Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEach { v ->
            Canvas(Modifier.weight(1f).height((48 * (v / max)).dp.coerceAtLeast(4.dp))) {
                drawRect(accent)
            }
        }
    }
}

@Composable
private fun GaugeRing(value: Double?, max: Double, color: Color) {
    val sweep = value?.let { ((it / max).coerceIn(0.0, 1.0) * 270).toFloat() } ?: 0f
    val track = OnWallpaper.copy(alpha = 0.25f)
    Canvas(Modifier.size(56.dp)) {
        drawArc(track, 135f, 270f, useCenter = false, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
        if (sweep > 0) drawArc(color, 135f, sweep, useCenter = false, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun tinted(style: WidgetStyle?): Color {
    val tint = style?.tint?.lowercase()
    val tokens = MasalaTheme.tokens
    return when (tint) {
        "accent", "blue" -> tokens.accent
        "green" -> Color(0xFF4CAF50)
        "amber", "orange" -> Color(0xFFFFB300)
        "red" -> Color(0xFFE57373)
        else -> OnWallpaper
    }
}

private fun alignOf(style: WidgetStyle?): TextAlign = when (style?.align) {
    Align.CENTER -> TextAlign.Center
    Align.END -> TextAlign.End
    else -> TextAlign.Start
}

private fun trim(v: Double): String {
    val asLong = v.toLong()
    return if (v == asLong.toDouble()) asLong.toString() else v.toString()
}

private fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** A stack draws its first widget, like before, but now for real (tap weights order it in W2). */
@Composable
fun StackCard(widgets: List<WidgetSpec>, rows: Map<String, Int>, source: WidgetSource, modifier: Modifier = Modifier) {
    val first = widgets.firstOrNull() ?: return
    WidgetCard(first, rows[first.id], source, modifier)
    if (widgets.size > 1) {
        Text(
            "+ ${widgets.size - 1} more",
            style = MaterialTheme.typography.labelMedium,
            color = MasalaTheme.tokens.onWallpaperMedium,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        )
    }
}
