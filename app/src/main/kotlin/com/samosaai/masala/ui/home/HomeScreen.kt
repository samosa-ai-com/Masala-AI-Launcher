package com.samosaai.masala.ui.home

import android.content.pm.ShortcutInfo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.layout.ResolvedLayout
import com.samosaai.masala.launcher.IconCache
import com.samosaai.masala.launcher.rememberAppIcon
import com.samosaai.masala.launcher.rememberShortcutIcon
import com.samosaai.masala.ui.components.LauncherRow
import com.samosaai.masala.ui.components.wallpaperText

/**
 * The drawer following the finger (plan UI refresh U0). Home feeds it upward travel in pixels while the
 * finger is down and the release velocity (px/s, negative is upward) when it lifts; the drawer decides
 * whether to settle open or closed.
 */
interface DrawerDrag {
    fun onDrag(upPx: Float)
    fun onRelease(velocityY: Float)
}

/**
 * The home screen (plan Phase 2): the resolved layout drawn by [LayoutRenderer], the default-home banner
 * on top, shortcuts other apps pinned below the layout, and the gestures. A vertical swipe runs the
 * user's swipe-up or swipe-down action; a long press on empty space opens settings.
 */
@Composable
fun HomeScreen(
    layout: ResolvedLayout,
    render: RenderContext,
    pinnedShortcuts: List<ShortcutInfo>,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Empty-space long-press: the home menu (wallpaper, widgets, settings). */
    onAddWidget: () -> Unit = onOpenSettings,
    onLaunchShortcut: (ShortcutInfo) -> Unit,
    onUnpinShortcut: (ShortcutInfo) -> Unit,
    homePresses: Int = 0,
    /** When set, a swipe up moves the drawer with the finger instead of [onSwipeUp] firing on lift. */
    drawerDrag: DrawerDrag? = null,
    /** Above the dock: the search-or-ask pill (UI refresh U3). */
    aboveDock: @Composable () -> Unit = {},
    /** Bumped when a new layout is applied, so its sections arrive one by one. */
    entrySignal: Int = 0,
    /** False when embedded in another screen that already handles insets (e.g. the AI preview). */
    applyWindowInsets: Boolean = true,
    /** True while any finger is down on home, false once every finger is up (or tracking ends). */
    onFingerActive: (Boolean) -> Unit = {},
) {
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    val swipeUpLabel = stringResource(R.string.home_swipe_up_description)
    val swipeDownLabel = stringResource(R.string.home_swipe_down_description)
    val openSettingsLabel = stringResource(R.string.home_open_settings_description)
    // Two detectors feed one decision, because neither sees every gesture alone. A scrollable
    // child consumes the drag, so the outer drag detector only survives on empty space; the
    // nested connection sees both what the list used (to disqualify the gesture) and what it
    // couldn't (peak overscroll). The drawer and the swipe actions engage only for gestures
    // that never moved content, and the action fires exactly once, on finger lift — so a trip
    // to the end of a long layout, and the overscroll past it, never trigger anything.
    //
    // The tracker object is stable across recompositions (callbacks go through UpdatedState);
    // otherwise a recomposition mid-swipe would restart tracking and swallow the gesture.
    val latestUp by rememberUpdatedState(onSwipeUp)
    val latestDown by rememberUpdatedState(onSwipeDown)
    val latestDrawer by rememberUpdatedState(drawerDrag)
    val latestFinger by rememberUpdatedState(onFingerActive)
    val slop = with(LocalDensity.current) { 12.dp.toPx() }
    val swipes = remember(swipeThreshold, slop) {
        object {
            var peakUp = 0f
            var peakDown = 0f
            var tracking = false
            var handled = false
            // Live drawer tracking: net travel since the gesture started, and whether the drawer took it.
            var net = 0f
            var following = false
            // Whether any home content moved vertically during this gesture. A swipe that
            // scrolled the layout must never open the drawer or fire the swipe actions —
            // not when it reaches the end, not from the overscroll past it, not on lift.
            // Only a gesture that never moved content (empty space, or a list already
            // parked at its end) counts as a swipe. Set from consumed scroll, so it latches
            // even while the list still has room and only overscroll reaches the tracker.
            var contentMoved = false
            // Set by the lift observer for the span of a gesture. The list's drag deltas reach the
            // nested connection through a channel, so a quick flick's last deltas can land after
            // the lift: taken then, they started a new drag with no lift left to settle it,
            // stranding the drawer mid-slide.
            var fingerDown = false
            // Fed from the pointer events (their own timestamps) by the lift observer below.
            val velocity = VelocityTracker()

            val connection = object : NestedScrollConnection {
                // While the drawer follows the finger it owns the gesture outright: consume
                // here so the list underneath can't start scrolling mid-drag and freeze it.
                // The delta must still reach the tracker from here: the list's drag consumes
                // the pointer changes (cancelling the outer detector), and nothing is left for
                // onPostScroll, so this is the only path the drag still flows through.
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && following) {
                        add(available.y)
                        return Offset(available.x, available.y)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && (consumed.y != 0f || available.y != 0f)) {
                        ensureTracking()
                        if (consumed.y != 0f) contentMoved = true
                        if (available.y != 0f) add(available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero
            }

            fun ensureTracking() {
                if (!tracking) {
                    tracking = true
                    peakUp = 0f
                    peakDown = 0f
                    handled = false
                    net = 0f
                    following = false
                    contentMoved = false
                }
            }

            fun add(dy: Float) {
                if (!fingerDown) return
                ensureTracking()
                net += dy
                val drawer = latestDrawer
                // Upward travel past the slop hands the gesture to the drawer, which then owns
                // it and follows the finger both ways until lift — but only when no content
                // moved: pushing past the end of a scroll is still a scroll, never a drawer open.
                if (drawer != null && (following || (!contentMoved && -net > slop && -net > peakDown))) {
                    following = true
                    drawer.onDrag((-net).coerceAtLeast(0f))
                }
                // Peaks, not a running total: lift jitter or a mid-gesture wiggle can shrink
                // the current run, but a swipe that already traveled far enough still counts.
                if (dy < 0) peakUp = minOf(peakUp, peakUp + dy) else peakDown = maxOf(peakDown, peakDown + dy)
            }

            fun onLift() {
                if (!tracking) return
                tracking = false
                if (handled) {
                    // A lift with no fresh tracking (e.g. after a missed one): drop the stale
                    // peaks so the next gesture starts clean instead of inheriting them.
                    handled = false
                    peakUp = 0f
                    peakDown = 0f
                    net = 0f
                    following = false
                    contentMoved = false
                    return
                }
                handled = true
                if (following) {
                    following = false
                    val vy = runCatching { velocity.calculateVelocity().y }.getOrDefault(0f)
                    latestDrawer?.onRelease(if (vy.isFinite()) vy else 0f)
                } else {
                    // The further excursion wins; ties and sub-threshold lifts do nothing. A gesture
                    // that scrolled content fires neither action: its overscroll peaks belong to the
                    // scroll, not to a swipe.
                    if (-peakUp >= swipeThreshold && -peakUp >= peakDown && !contentMoved) latestUp()
                    else if (peakDown >= swipeThreshold && peakDown > -peakUp && !contentMoved) latestDown()
                }
                peakUp = 0f
                peakDown = 0f
                net = 0f
                following = false
                contentMoved = false
                handled = false
            }
        }
    }
    // Finger lift, observed without consuming anything: the one place swipe actions fire, so a
    // trip to the end of a long layout never triggers anything by itself.
    Box(
        Modifier
            .fillMaxSize()
            .nestedScroll(swipes.connection)
            .pointerInput(swipes) {
                // The outer drag detector: the only path that sees swipes on empty space,
                // where there is no scrollable child to start nested scrolling. Feeds the
                // same tracker; the lift below decides and fires exactly once.
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        dragged = 0f
                        swipes.add(0f)
                    },
                    onDragEnd = {
                        // No-op on purpose: the Final-pass observer below fires onLift() once it has
                        // seen the up event, so the release velocity includes it. Firing here (Main
                        // pass) would compute velocity without the up change.
                    },
                    onDragCancel = { /* the nested path may still be live; lift decides */ },
                    onVerticalDrag = { change, dy ->
                        dragged += dy
                        swipes.add(dy)
                        change.consume()
                    },
                )
            }
            .pointerInput(Unit) {
                // Observes only (consumes nothing): when every finger is up, the gesture is
                // over, which is the single moment a tracked swipe fires. Unit key: never
                // restarts, so a recomposition mid-swipe cannot swallow the lift.
                // Final pass on purpose: siblings may consume the up event (which cancels
                // Main-pass waiters), but Final still sees it.
                while (true) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        swipes.fingerDown = true
                        latestFinger(true)
                        try {
                            swipes.velocity.resetTracking()
                            swipes.velocity.addPointerInputChange(down)
                            // The release velocity follows one finger; when it lifts while another
                            // is still down, tracking moves to that one, so its fling counts.
                            var tracked = down.id
                            while (true) {
                                // Bounded per event, not per gesture: if the stream dies with no
                                // lift and no cancel (a system steal mid-gesture), two seconds of
                                // silence settles by position. A slow drag that keeps moving is
                                // never cut off.
                                val event = withTimeoutOrNull(2_000) { awaitPointerEvent(PointerEventPass.Final) } ?: break
                                val change = event.changes.firstOrNull { it.id == tracked }
                                if (change != null && !change.pressed) {
                                    event.changes.firstOrNull { it.pressed }?.let { next ->
                                        tracked = next.id
                                        swipes.velocity.resetTracking()
                                        swipes.velocity.addPointerInputChange(next)
                                    } ?: swipes.velocity.addPointerInputChange(change)
                                } else {
                                    change?.let(swipes.velocity::addPointerInputChange)
                                }
                                // The gesture is over only once every finger is up: ending it when
                                // just the first finger lifts (while a second is still down) fired
                                // the release early, and the remaining finger's drag then moved the
                                // drawer with no lift left to settle it — freezing it mid-slide.
                                // A lifted pointer can still vanish from later events instead of
                                // arriving pressed=false: an empty change list ends it too.
                                if (event.changes.isEmpty() || event.changes.all { !it.pressed }) break
                            }
                            swipes.fingerDown = false
                            swipes.onLift()
                        } finally {
                            swipes.fingerDown = false
                            latestFinger(false)
                        }
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onAddWidget() }) }
            // TalkBack can't swipe or long-press empty space, so each is offered as an action.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(swipeUpLabel) { onSwipeUp(); true },
                    CustomAccessibilityAction(swipeDownLabel) { onSwipeDown(); true },
                    CustomAccessibilityAction(openSettingsLabel) { onOpenSettings(); true },
                )
            }
            .then(if (applyWindowInsets) Modifier.systemBarsPadding() else Modifier),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!isDefault) DefaultBanner(onSetDefault)
            LayoutRenderer(layout, render, Modifier.weight(1f), resetSignal = homePresses, aboveDock = aboveDock, entrySignal = entrySignal) {
                if (pinnedShortcuts.isNotEmpty()) {
                    Text(
                        stringResource(R.string.home_shortcuts_title),
                        style = wallpaperText(MaterialTheme.typography.titleSmall),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    pinnedShortcuts.forEach { ShortcutRow(it, render.icons, onLaunchShortcut, onUnpinShortcut) }
                }
            }
        }
    }
}

@Composable
private fun DefaultBanner(onSetDefault: () -> Unit) {    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.home_set_default_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.heightIn(min = 8.dp))
            Button(onClick = onSetDefault) { Text(stringResource(R.string.home_set_default_action)) }
        }
    }
}

@Composable
private fun ShortcutRow(
    shortcut: ShortcutInfo,
    icons: IconCache,
    onLaunch: (ShortcutInfo) -> Unit,
    onUnpin: (ShortcutInfo) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.id).toString()
    Box {
        LauncherRow(
            label = label,
            icon = rememberShortcutIcon(icons, shortcut),
            onClick = { onLaunch(shortcut) },
            onLongClick = { menu = true },
            longClickLabel = stringResource(R.string.menu_options),
            textStyle = wallpaperText(MaterialTheme.typography.headlineSmall),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shortcut_unpin)) },
                onClick = {
                    menu = false
                    onUnpin(shortcut)
                },
            )
        }
    }
}
