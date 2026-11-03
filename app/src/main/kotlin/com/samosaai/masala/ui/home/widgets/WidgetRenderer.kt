package com.samosaai.masala.ui.home.widgets

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    /** Hosts the user approved, per widget; the web_value view won't fetch without approval. */
    val approved: (widgetId: String) -> Set<String> = { emptySet() },
    /** Values only the app can read, keyed for `second_brain` bindings ("notes", "latest"). */
    val extra: () -> Map<String, String> = { emptyMap() },
    /** False keeps animated pieces (the globe) still, for long lists that render off screen. */
    val animate: Boolean = true,
)

/**
 * One generated widget on home: a glass card, its title at the top and its components together at the
 * bottom, so a card taller than its content reads as a widget rather than an empty box.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetCard(spec: WidgetSpec, rows: Int?, source: WidgetSource, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val snapshot = source.snapshot()
    val extra = source.extra()
    val states by source.store.states.collectAsState()
    // New values (a counter tap, a checklist tick) recompose this card alone.
    LaunchedEffect(spec.id) { source.store.load(listOf(spec.id)) }
    val storage = states[spec.id].orEmpty()
    val longPress = source.onLongPress
    WallpaperCard(
        modifier.fillMaxWidth().heightIn(min = ((rows ?: spec.size.default) * 48).dp)
            .then(if (longPress != null) Modifier.combinedClickable(onClick = {}, onLongClick = longPress) else Modifier),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        spec.title?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = OnWallpaper, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(if (spec.title != null) Modifier.padding(top = 4.dp) else Modifier) {
            spec.components.forEach { component ->
                Component(component, spec.id, storage, snapshot, source, depth = 0, enabled = enabled, title = spec.title, extra = extra)
            }
        }
    }
}

/** A label that repeats the card's title ("Tea" over "Tea") is left out. */
private fun labelShown(label: String, title: String?): Boolean =
    title == null || !label.trim().equals(title.trim(), ignoreCase = true)

@Composable
private fun Component(
    component: WidgetComponent,
    widgetId: String,
    storage: Map<String, String>,
    snapshot: ContextSnapshot,
    source: WidgetSource,
    depth: Int,
    enabled: Boolean = true,
    /** The card's title, so a label repeating it is dropped. */
    title: String? = null,
    /** App-supplied values for `second_brain` bindings. */
    extra: Map<String, String> = emptyMap(),
) {
    // A refresh button with no id means this widget; the renderer fills it before dispatching.
    fun fire(action: com.samosaai.masala.core.contract.MasalaAction) =
        source.onAction(
            if (action is com.samosaai.masala.core.contract.MasalaAction.RefreshWidget && action.widgetId.isBlank()) {
                action.copy(widgetId = widgetId)
            } else {
                action
            },
        )
    when (component) {
        is WidgetComponent.Text -> {
            val text = component.text ?: component.binding?.let { WidgetValues.textOf(it, storage, snapshot, extra) } ?: return
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
                    if (labelShown(component.label, title)) Text(component.label, style = MaterialTheme.typography.labelLarge, color = MasalaTheme.tokens.onWallpaperMedium)
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
        is WidgetComponent.Timer -> TimerRow(component, widgetId, storage, source, labelShown(component.label, title))
        is WidgetComponent.Progress -> {
            val value = component.binding.let { WidgetValues.numberOf(it, storage, snapshot, extra) }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (labelShown(component.label, title)) {
                        Text(component.label, style = MaterialTheme.typography.labelLarge, color = MasalaTheme.tokens.onWallpaperMedium)
                    } else {
                        // No label (it repeats the title): the value still sits at the trailing edge.
                        Spacer(Modifier.weight(1f))
                    }
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
            Button(onClick = { fire(component.action) }, modifier = Modifier.fillMaxWidth()) { Text(component.label) }
        }
        is WidgetComponent.Image -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                com.samosaai.masala.ui.home.SymbolDisc(SymbolIcons.icon((component.icon as? com.samosaai.masala.core.contract.IconRef.Symbol)?.name ?: "star"), null, 40)
            }
        }
        is WidgetComponent.ListRows -> {
            val rows = component.binding.let { WidgetValues.rowsOf(it, storage, snapshot, component.maxRows, extra) }
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
                                .then(component.rowAction?.let { action -> Modifier.clickable(role = Role.Button) { fire(action) } } ?: Modifier),
                        )
                    }
                }
            }
        }
        is WidgetComponent.Gauge -> {
            val value = component.binding.let { WidgetValues.numberOf(it, storage, snapshot, extra) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GaugeRing(value, component.max, tinted(component.style))
                Column {
                    if (labelShown(component.label, title)) Text(component.label, style = MaterialTheme.typography.labelLarge, color = MasalaTheme.tokens.onWallpaperMedium)
                    Text(value?.let { trim(it) } ?: "—", style = MaterialTheme.typography.titleLarge, color = tinted(component.style))
                }
            }
        }
        is WidgetComponent.Globe -> {
            val raw = component.binding?.let { WidgetValues.numberOf(it, storage, snapshot, extra) }
            val accent = tinted(component.style)
            Globe(
                value = raw?.let { trim(it) } ?: component.binding?.let { WidgetValues.textOf(it, storage, snapshot, extra) },
                label = component.label,
                accent = accent,
                animate = source.animate,
            )
        }
        is WidgetComponent.Row -> {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                component.children.forEach { child ->
                    Column(Modifier.weight(1f)) { Component(child, widgetId, storage, snapshot, source, depth + 1, enabled, title, extra) }
                }
            }
        }
        is WidgetComponent.Column -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                component.children.forEach { child -> Component(child, widgetId, storage, snapshot, source, depth + 1, enabled, title, extra) }
            }
        }
        // Live media (W4): MJPEG and snapshots draw; player kinds need the pool.
        is WidgetComponent.Video -> {
            val streams = source.streams
            val src = streams?.current()?.get(component.stream.sourceId)
            val media = source.media
            when {
                src == null -> MediaNote(stringResource(R.string.widgets_video_no_source))
                src.kind == com.samosaai.masala.widgets.StreamKind.MJPEG && streams != null ->
                    com.samosaai.masala.widgets.media.MjpegImage(src.id, streams, enabled = enabled)
                media != null && streams != null ->
                    com.samosaai.masala.widgets.media.LiveVideo(src.id, streams, media, enabled = enabled)
                else -> MediaNote(stringResource(R.string.widgets_video_player_next))
            }
        }
        is WidgetComponent.Snapshot -> {
            val streams = source.streams
            val src = streams?.current()?.get(component.sourceId)
            if (src == null || streams == null) MediaNote(stringResource(R.string.widgets_video_no_source))
            else com.samosaai.masala.widgets.media.SnapshotImage(src.id, component.everySeconds, streams, enabled = enabled)
        }
        is WidgetComponent.WebValue -> {
            val store = source.http
            if (store == null) {
                if (labelShown(component.label, title)) Text(
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
                val host = com.samosaai.masala.widgets.HttpApproval.hostOf(component.http.url)
                com.samosaai.masala.widgets.media.WebValueView(
                    component, widgetId, component.suffix, store, component.style,
                    allowed = host != null && host in source.approved(widgetId),
                    enabled = enabled,
                    showLabel = labelShown(component.label, title),
                )
            }
        }
    }
}

@Composable
private fun MediaNote(note: String) {
    Text(note, style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun TimerRow(component: WidgetComponent.Timer, widgetId: String, storage: Map<String, String>, source: WidgetSource, showLabel: Boolean = true) {
    // A stopped timer has no deadline (empty, not "0"): "0" is an elapsed deadline and used to
    // ring the chime the moment someone pressed Stop.
    val deadline = storage[component.key]?.toLongOrNull()?.takeIf { it > 0 }
    // The shown time has to be state, not a clock read: composition only redraws when state
    // changes, so a bare System.currentTimeMillis() left the digits frozen until something else
    // refreshed the card (seen as a half-minute jump). The effect below advances it every second
    // while the timer runs, and settles once it is done.
    var now by remember(deadline) { mutableLongStateOf(System.currentTimeMillis()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(deadline) {
        if (deadline == null) return@LaunchedEffect
        while (deadline > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
        // The chime is for the moment it lands, once: the deadline itself is the token, so a
        // recomposition or coming back to home doesn't ring it again.
        if (component.sound && source.store.values(widgetId)[ringKey(component.key)] != deadline.toString()) {
            com.samosaai.masala.widgets.WidgetSounds.chime(context)
            source.store.set(widgetId, ringKey(component.key), deadline.toString())
        }
    }
    val left = if (deadline == null) null else (deadline - now).coerceAtLeast(0)
    val running = left != null && left > 0
    // The time as the card's hero, the action as one round accent button beside it.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            if (showLabel) Text(component.label, style = MaterialTheme.typography.labelLarge, color = MasalaTheme.tokens.onWallpaperMedium)
            Text(
                left?.let { formatDuration(it) } ?: formatDuration(component.seconds * 1000L),
                // Tabular figures: the digits don't shift while it counts down.
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                color = OnWallpaper,
            )
        }
        FilledIconButton(
            onClick = {
                source.store.set(
                    widgetId,
                    component.key,
                    if (running) "" else (System.currentTimeMillis() + component.seconds * 1000L).toString(),
                )
            },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (running) R.string.widget_timer_reset else R.string.widget_timer_start),
            )
        }
    }
}

/**
 * A slow wireframe globe: a soft core, a rim, meridians that turn and flat latitudes. The motion is
 * one `animateFloat` read inside the draw phase, so it never recomposes the card, and it stops when
 * the card is off screen.
 */
@Composable
private fun Globe(value: String?, label: String?, accent: Color, animate: Boolean) {
    // A still card keeps a readable angle instead of starting an animation nothing can see.
    val spin by if (animate) {
        rememberInfiniteTransition(label = "globe").animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 9_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "spin",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    }
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().height(118.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(112.dp)) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.34f), Color.Transparent),
                    center = center,
                    radius = r,
                ),
                radius = r,
            )
            drawCircle(accent.copy(alpha = 0.55f), radius = r, style = Stroke(width = 1.2.dp.toPx()))
            // Meridians: ellipses whose width follows the turn, so the sphere reads as 3D.
            repeat(3) { i ->
                val phase = (spin + i * (Math.PI / 3).toFloat()).toDouble()
                val w = (kotlin.math.abs(kotlin.math.cos(phase)) * r * 2.0).toFloat().coerceAtLeast(0.6f)
                drawOval(
                    color = accent.copy(alpha = 0.28f),
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - w / 2f, center.y - r),
                    size = androidx.compose.ui.geometry.Size(w, 2 * r),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            // Latitudes stay fixed; they are what makes the turning lines read as a globe.
            listOf(-0.55f, 0f, 0.55f).forEach { at ->
                val y = center.y + at * r
                val half = (r * kotlin.math.sqrt((1.0 - at * at).coerceAtLeast(0.0))).toFloat()
                drawLine(
                    accent.copy(alpha = 0.22f),
                    androidx.compose.ui.geometry.Offset(center.x - half, y),
                    androidx.compose.ui.geometry.Offset(center.x + half, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            value?.let { Text(it, style = MaterialTheme.typography.headlineSmall, color = accent) }
            label?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MasalaTheme.tokens.onWallpaperMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Where a timer records the deadline it already rang for. */
private fun ringKey(key: String) = "$key.rang"

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
fun StackCard(widgets: List<WidgetSpec>, rows: Int?, source: WidgetSource, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val first = widgets.firstOrNull() ?: return
    WidgetCard(first, rows, source, modifier, enabled)
    if (widgets.size > 1) {
        Text(
            "+ ${widgets.size - 1} more",
            style = MaterialTheme.typography.labelMedium,
            color = MasalaTheme.tokens.onWallpaperMedium,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        )
    }
}
