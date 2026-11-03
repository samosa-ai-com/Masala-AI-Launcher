package com.samosaai.masala.ui.home.widgets

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.samosaai.masala.core.contract.resolved
import com.samosaai.masala.core.contract.WidgetStyle
import com.samosaai.masala.core.widgets.WidgetValues
import com.samosaai.masala.ui.home.SymbolIcons
import com.samosaai.masala.ui.home.WallpaperCard
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.theme.OnWallpaper
import com.samosaai.masala.widgets.HttpValueStore
import com.samosaai.masala.widgets.WidgetStateStore
import com.samosaai.masala.widgets.WidgetTimers
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
    // The widget's own settings (goal, duration, place, items) apply to a copy of the spec, so a
    // shared template can be configured per placement without rewriting the library row.
    val prefs = remember(storage) { com.samosaai.masala.widgets.WidgetPrefsStore.of(storage) }
    val shown = remember(spec, prefs) { spec.resolved(prefs) }
    LaunchedEffect(spec.id, prefs, snapshot.now.toLocalDate()) {
        com.samosaai.masala.widgets.WidgetRules.apply(source.store, spec.id, spec, prefs, snapshot.now.toLocalDate())
    }
    WallpaperCard(
        modifier.fillMaxWidth().heightIn(min = ((rows ?: spec.size.default) * 48).dp)
            .then(if (longPress != null) Modifier.combinedClickable(onClick = {}, onLongClick = longPress) else Modifier),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        shown.title?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = OnWallpaper, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(if (shown.title != null) Modifier.padding(top = 4.dp) else Modifier) {
            shown.components.forEachIndexed { index, component ->
                Component(
                    component, spec.id, storage, snapshot, source, depth = 0, enabled = enabled,
                    title = shown.title, extra = extra,
                    // Keyed paths so a component keeps its settings when the template moves.
                    prefs = prefs.components[com.samosaai.masala.core.contract.prefsKey(component, index)],
                    timers = shown.components.filterIsInstance<WidgetComponent.Timer>(),
                )
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
    /** The widget's override for this component; null keeps the spec. */
    prefs: com.samosaai.masala.core.contract.ComponentPrefs? = null,
    /** Every timer in this widget, so a finished phase can start the next one. */
    timers: List<WidgetComponent.Timer> = emptyList(),
    /** A child of a Row shares the width; a full-width button would squeeze its siblings to zero. */
    inRow: Boolean = false,
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
            val bound = component.binding?.let { WidgetValues.textOf(it, storage, snapshot, extra) }
            if (component.text == null && bound == null) {
                SetupNote(component.binding?.source) { fire(it) }
                return
            }
            // A binding with a value shows it; a static line is its own text.
            val text = component.text ?: bound ?: "—"
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
            val target = component.date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            if (target != null) {
                // A countdown is a date, not a number to tap: the days left are computed, and the
                // bar fills as the date approaches (goal = the window it was set for).
                val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), target)
                val goal = component.goal?.coerceAtLeast(1)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (labelShown(component.label, title)) {
                        Text(component.label, style = MaterialTheme.typography.labelMedium, color = MasalaTheme.tokens.onWallpaperMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        when {
                            days > 1L -> "$days days left"
                            days == 1L -> "Tomorrow"
                            days == 0L -> "Today"
                            else -> "${-days} days ago"
                        },
                        style = if (component.style?.emphasis == Emphasis.LARGE) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                        color = tinted(component.style),
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (goal != null) {
                        val elapsed = (goal - days.coerceIn(0, goal.toLong())).toFloat() / goal
                        LinearProgressIndicator(
                            progress = { elapsed.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
                return
            }
            val value = source.store.int(widgetId, component.key)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (labelShown(component.label, title)) {
                            Text(
                                component.label,
                                // labelMedium so a 2-cell card keeps "Blocks today" whole
                                // beside the step chips.
                                style = MaterialTheme.typography.labelMedium,
                                color = MasalaTheme.tokens.onWallpaperMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            if (component.goal != null) "$value / ${component.goal}" else value.toString(),
                            style = if (component.style?.emphasis == Emphasis.LARGE) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                            color = tinted(component.style),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { source.store.set(widgetId, component.key, "0") },
                            ),
                        )
                    }
                    // Compact steps: a full Button beside a TextButton squeezed a 2-cell card until
                    // its label wrapped one letter per line (seen in the gallery preview).
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        StepChip(if (component.step == 1) "−" else "−${component.step}") {
                            if (value > 0) source.store.increment(widgetId, component.key, -component.step)
                        }
                        StepChip(if (component.step == 1) "+" else "+${component.step}") {
                            source.store.increment(widgetId, component.key, component.step)
                        }
                    }
                }
                component.goal?.let { goal ->
                    LinearProgressIndicator(
                        progress = { (value.toFloat() / goal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }
        }
        is WidgetComponent.Checklist -> ChecklistRow(component, widgetId, storage, source)
        is WidgetComponent.Timer -> TimerRow(
            component, widgetId, storage, source, labelShown(component.label, title),
            chain = timers.ifEmpty { listOf(component) },
        )
        is WidgetComponent.Progress -> {
            val value = component.binding.let { WidgetValues.numberOf(it, storage, snapshot, extra) }
            if (value == null && component.binding.source != com.samosaai.masala.core.contract.BindingSource.STORAGE) {
                SetupNote(component.binding.source) { fire(it) }
                return
            }
            val storageKey = component.binding.key?.takeIf {
                component.binding.source == com.samosaai.masala.core.contract.BindingSource.STORAGE
            }
            var adding by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (labelShown(component.label, title)) {
                        Text(component.label, style = MaterialTheme.typography.labelLarge, color = MasalaTheme.tokens.onWallpaperMedium)
                    } else {
                        // No label (it repeats the title): the value still sits at the trailing edge.
                        Spacer(Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(value?.let { trim(it) } ?: "—", style = MaterialTheme.typography.labelLarge, color = tinted(component.style))
                        // A budget that cannot be written to is a decoration; this is its entry.
                        if (storageKey != null) {
                            TextButton(onClick = { adding = true }) { Text("+") }
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = { value?.let { (it / component.max).toFloat().coerceIn(0f, 1f) } ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            if (adding && storageKey != null) {
                AmountDialog(
                    title = component.label,
                    onAdd = { amount ->
                        source.store.set(widgetId, storageKey, (source.store.values(widgetId)[storageKey]?.toDoubleOrNull() ?: 0.0 + amount).toString())
                        adding = false
                    },
                    onSet = { amount ->
                        source.store.set(widgetId, storageKey, amount.toString())
                        adding = false
                    },
                    onDismiss = { adding = false },
                )
            }
        }
        is WidgetComponent.Chart -> ChartBars(component, storage, snapshot)
        is WidgetComponent.Button -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            Button(
                onClick = {
                    val flip = component.action as? com.samosaai.masala.core.contract.MasalaAction.Flip
                    if (flip == null) {
                        fire(component.action)
                    } else {
                        // A coin flip belongs on the card, not in chat: write the result and the
                        // text row above shows it at once.
                        val result = if (flip.kind == "dice") (1..6).random().toString()
                        else if (kotlin.random.Random.nextBoolean()) "Heads" else "Tails"
                        source.store.set(widgetId, flip.key, result)
                        com.samosaai.masala.widgets.WidgetSounds.click(context)
                    }
                },
                modifier = if (inRow) Modifier else Modifier.fillMaxWidth(),
            ) { Text(component.label) }
        }
        is WidgetComponent.Image -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                com.samosaai.masala.ui.home.SymbolDisc(SymbolIcons.icon((component.icon as? com.samosaai.masala.core.contract.IconRef.Symbol)?.name ?: "star"), null, 40)
            }
        }
        is WidgetComponent.ListRows -> {
            val rows = component.binding.let { WidgetValues.rowsOf(it, storage, snapshot, component.maxRows, extra) }
            if (rows.isEmpty()) {
                SetupNote(component.binding.source) { fire(it) }
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
            if (value == null) {
                SetupNote(component.binding.source) { fire(it) }
                return
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GaugeRing(value, component.max, tinted(component.style))
                Column {
                    if (labelShown(component.label, title)) Text(component.label, style = MaterialTheme.typography.labelLarge, color = MasalaTheme.tokens.onWallpaperMedium)
                    Text(value?.let { trim(it) } ?: "—", style = MaterialTheme.typography.titleLarge, color = tinted(component.style))
                }
            }
        }
        is WidgetComponent.Globe -> {
            val binding = component.binding
            val raw = binding?.let { WidgetValues.numberOf(it, storage, snapshot, extra) }
            val text = binding?.let { WidgetValues.textOf(it, storage, snapshot, extra) }
            if (binding != null && raw == null && text == null) {
                SetupNote(binding.source) { fire(it) }
                return
            }
            val accent = tinted(component.style)
            Globe(
                value = raw?.let { trim(it) } ?: text,
                label = component.label,
                accent = accent,
                animate = source.animate,
            )
        }
        is WidgetComponent.Row -> {
            // Two timers or two gauges side by side need room; on a narrow placement (2 cells)
            // they stack, so no child is squeezed to an ellipsis ("…" instead of "25:00").
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Only clock/gauge pairs need ~100 dp each; a label and a small button fit side by
                // side even on a 2-cell card.
                val wideChildren = component.children.any {
                    it is WidgetComponent.Timer || it is WidgetComponent.Gauge
                }
                val stacked = wideChildren && maxWidth < 220.dp
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        component.children.forEach { child ->
                            Component(child, widgetId, storage, snapshot, source, depth + 1, enabled, title, extra, timers = timers)
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        component.children.forEach { child ->
                            // A button keeps its own width; an equal share made "Flip" wrap to "Fli p".
                            val sizing = if (child is WidgetComponent.Button) Modifier else Modifier.weight(1f)
                            Column(sizing) { Component(child, widgetId, storage, snapshot, source, depth + 1, enabled, title, extra, timers = timers, inRow = true) }
                        }
                    }
                }
            }
        }
        is WidgetComponent.Column -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                component.children.forEach { child -> Component(child, widgetId, storage, snapshot, source, depth + 1, enabled, title, extra, timers = timers) }
            }
        }
        // Live media (W4): MJPEG and snapshots draw; player kinds need the pool.
        is WidgetComponent.Video -> {
            val streams = source.streams
            val src = streams?.current()?.get(component.stream.sourceId)
            val media = source.media
            when {
                src == null -> Column {
                    MediaNote(stringResource(R.string.widgets_video_no_source))
                    TextButton(onClick = { fire(com.samosaai.masala.core.contract.MasalaAction.OpenSettings("WIDGETS")) }) {
                        Text(stringResource(R.string.widget_set_up))
                    }
                }
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
            if (src == null || streams == null) {
                Column {
                    MediaNote(stringResource(R.string.widgets_video_no_source))
                    TextButton(onClick = { fire(com.samosaai.masala.core.contract.MasalaAction.OpenSettings("WIDGETS")) }) {
                        Text(stringResource(R.string.widget_set_up))
                    }
                }
            }
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

/**
 * What a card says when the phone cannot supply what it binds to, plus the one tap that fixes it:
 * weather without a place, steps/sleep without Health Connect, calendar without access. A dash
 * told the user nothing and offered no way forward.
 */
@Composable
private fun SetupNote(source: com.samosaai.masala.core.contract.BindingSource?, fire: (com.samosaai.masala.core.contract.MasalaAction) -> Unit) {
    val text = when (source) {
        com.samosaai.masala.core.contract.BindingSource.WEATHER -> R.string.widget_needs_weather
        com.samosaai.masala.core.contract.BindingSource.STEPS,
        com.samosaai.masala.core.contract.BindingSource.SLEEP -> R.string.widget_needs_health
        com.samosaai.masala.core.contract.BindingSource.NEXT_EVENT -> R.string.widget_needs_calendar
        else -> R.string.widget_nothing_yet
    }
    val page = when (source) {
        com.samosaai.masala.core.contract.BindingSource.WEATHER -> "BRIEF"
        com.samosaai.masala.core.contract.BindingSource.STEPS,
        com.samosaai.masala.core.contract.BindingSource.SLEEP -> "PERMISSIONS"
        com.samosaai.masala.core.contract.BindingSource.NEXT_EVENT -> "PERMISSIONS"
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium)
        if (page != null) {
            TextButton(onClick = {
                fire(com.samosaai.masala.core.contract.MasalaAction.OpenSettings(page))
            }) { Text(stringResource(R.string.widget_set_up)) }
        }
    }
}

@Composable
private fun MediaNote(note: String) {
    Text(note, style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun TimerRow(
    component: WidgetComponent.Timer,
    widgetId: String,
    storage: Map<String, String>,
    source: WidgetSource,
    showLabel: Boolean = true,
    /** Every timer in this widget, so a finished phase can start the next one. */
    chain: List<WidgetComponent.Timer> = listOf(component),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // The deadline is what is stored; the digits need a clock read that changes every second, so
    // the visible time is state (a plain read froze until something else recomposed the card).
    var now by remember(storage[component.key]) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(storage[component.key], component.key) {
        val deadline = WidgetTimers.state(source.store, widgetId, component.key).deadline
            ?: return@LaunchedEffect
        while (deadline > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        // The alarm is the primary path; this catches a denied exact alarm or a reboot, and is the
        // only path when the screen is on and the card is already composed.
        WidgetTimers.fire(context, source.store, widgetId, component.key, component.sound) { chain }
    }
    val state = remember(storage[component.key], storage[WidgetTimers.leftKey(component.key)]) {
        WidgetTimers.state(source.store, widgetId, component.key)
    }
    val left = when {
        state.deadline != null -> (state.deadline - now).coerceAtLeast(0)
        state.paused -> state.pausedLeftMs ?: 0
        else -> component.seconds * 1_000L
    }
    val running = state.running
    val paused = state.paused
    val active = running || paused
    // A finished timer shows its full duration again; a paused one keeps its remainder.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            if (showLabel) Text(component.label, style = MaterialTheme.typography.labelLarge, color = MasalaTheme.tokens.onWallpaperMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatDuration(left),
                // Tabular figures: the digits don't shift while it counts down. titleLarge (not
                // headline) so a 2-cell card keeps "25:00" on one line beside the button.
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                color = if (paused) MasalaTheme.tokens.onWallpaperMedium else OnWallpaper,
                maxLines = 1,
                softWrap = false,
                // Tapping a running timer stops it; the round button is play/pause/resume.
                modifier = if (active) Modifier.clickable(role = Role.Button) {
                    WidgetTimers.stop(context, source.store, widgetId, component)
                } else Modifier,
            )
            component.autoStartKey?.let { nextKey ->
                val next = chain.firstOrNull { it.key == nextKey }
                if (next != null && running) {
                    Text(
                        stringResource(R.string.widget_timer_then, next.label, formatDuration(next.seconds * 1_000L)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MasalaTheme.tokens.onWallpaperMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        FilledIconButton(
            onClick = {
                when {
                    running -> WidgetTimers.pause(context, source.store, widgetId, component)
                    paused -> WidgetTimers.resume(context, source.store, widgetId, component)
                    else -> WidgetTimers.start(context, source.store, widgetId, component)
                }
            },
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(
                    when {
                        running -> R.string.widget_timer_pause
                        paused -> R.string.widget_timer_resume
                        else -> R.string.widget_timer_start
                    },
                ),
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
        Modifier.fillMaxWidth().height(92.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(88.dp)) {
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

/** A widget checklist stays readable; the checker's own cap is 20. */
private const val MAX_CHECKLIST = 20

/** Where a timer records the deadline it already rang for. */
private fun ringKey(key: String) = "$key.rang"

/** Add or set a progress amount (a monthly budget, a trip fund). */
@Composable
private fun AmountDialog(
    title: String,
    onAdd: (Double) -> Unit,
    onSet: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val amount = text.replace(',', '.').toDoubleOrNull()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { v -> if (v.length <= 12) text = v },
                singleLine = true,
                label = { Text(stringResource(R.string.widget_amount)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                ),
            )
        },
        confirmButton = {
            TextButton(enabled = amount != null && amount >= 0, onClick = { amount?.let(onAdd) }) {
                Text(stringResource(R.string.widget_amount_add))
            }
        },
        dismissButton = {
            Row {
                TextButton(enabled = amount != null && amount >= 0, onClick = { amount?.let(onSet) }) {
                    Text(stringResource(R.string.widget_amount_set))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** A small −/+ control for a counter; the label sets the width, so a 2-cell card still fits. */
@Composable
private fun StepChip(label: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(MasalaTheme.tokens.onWallpaperMedium.copy(alpha = 0.16f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = OnWallpaper,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A checklist you can actually keep: tick an item, add one, rename or remove one, or reset the
 * whole list for a new day. The spec's items are the starting point; once edited, the widget keeps
 * its own list under `<key>.items`, so a template update never overwrites the user's list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChecklistRow(
    component: WidgetComponent.Checklist,
    widgetId: String,
    storage: Map<String, String>,
    source: WidgetSource,
) {
    val stored = storage["${component.key}.items"]
    val items = remember(stored, component.items) {
        stored?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(MAX_CHECKLIST) ?: component.items
    }
    val ticks = source.store.bools(widgetId, component.key, items.size)
    var editing by remember { mutableStateOf<Int?>(null) }
    fun persist(next: List<String>, nextTicks: List<Boolean>) {
        source.store.set(widgetId, "${component.key}.items", next.joinToString("\n"))
        source.store.set(widgetId, component.key, nextTicks.joinToString(",") { if (it) "1" else "0" })
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = { source.store.toggle(widgetId, component.key, i, items.size) },
                        onLongClick = { editing = i },
                    ),
            ) {
                Checkbox(
                    checked = ticks.getOrElse(i) { false },
                    onCheckedChange = { source.store.toggle(widgetId, component.key, i, items.size) },
                )
                Text(
                    item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ticks.getOrElse(i) { false }) MasalaTheme.tokens.onWallpaperMedium else OnWallpaper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { editing = i }) { Text("…") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { editing = items.size }, enabled = items.size < MAX_CHECKLIST) { Text("+ Add") }
            if (items.indices.any { ticks.getOrElse(it) { false } }) {
                TextButton(onClick = {
                    source.store.set(widgetId, component.key, items.joinToString(",") { "0" })
                }) { Text("Reset") }
            }
        }
    }
    editing?.let { index ->
        val existing = items.getOrNull(index).orEmpty()
        ChecklistItemDialog(
            initial = existing,
            canDelete = index < items.size,
            onSave = { text ->
                val next = items.toMutableList()
                val nextTicks = ticks.toMutableList()
                if (index < next.size) {
                    next[index] = text
                } else {
                    next += text
                    nextTicks += false
                }
                persist(next, nextTicks)
                editing = null
            },
            onDelete = if (index < items.size) {
                {
                    // The tick belongs to the item, not the position: dropping item 2 must keep
                    // the ticks of the items that move up.
                    persist(
                        items.filterIndexed { i, _ -> i != index },
                        ticks.filterIndexed { i, _ -> i != index },
                    )
                    editing = null
                }
            } else null,
            onDismiss = { editing = null },
        )
    }
}

/** Name one checklist item; a long-press on a row opens this too, with Delete. */
@Composable
private fun ChecklistItemDialog(
    initial: String,
    canDelete: Boolean,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (canDelete) "Edit item" else "New item") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 60) text = it },
                singleLine = true,
                label = { Text("Item") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onSave(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete") } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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
