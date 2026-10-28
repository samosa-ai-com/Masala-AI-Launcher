package com.samosaai.masala.ui.home

import android.content.pm.ShortcutInfo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
    onLaunchShortcut: (ShortcutInfo) -> Unit,
    onUnpinShortcut: (ShortcutInfo) -> Unit,
    homePresses: Int = 0,
    /** False when embedded in another screen that already handles insets (e.g. the AI preview). */
    applyWindowInsets: Boolean = true,
) {
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    val swipeUpLabel = stringResource(R.string.home_swipe_up_description)
    val swipeDownLabel = stringResource(R.string.home_swipe_down_description)
    val openSettingsLabel = stringResource(R.string.home_open_settings_description)
    // The page scrolls, and a scrollable keeps the drag even with nothing to scroll, so a swipe
    // arrives here as the scroll the list couldn't use. Only that leftover counts toward a
    // gesture — mid-list scrolling never does — and the action fires once, on finger lift, so a
    // trip to the end of a long layout never triggers anything by itself.
    //
    // The connection object is stable across recompositions (callbacks go through UpdatedState);
    // otherwise a recomposition mid-swipe would restart tracking and swallow the gesture.
    val latestUp by rememberUpdatedState(onSwipeUp)
    val latestDown by rememberUpdatedState(onSwipeDown)
    val swipes = remember(swipeThreshold) {
        object : NestedScrollConnection {
            var peakUp = 0f
            var peakDown = 0f
            var tracking = false

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    if (!tracking) {
                        tracking = true
                        peakUp = 0f
                        peakDown = 0f
                    }
                    // Peaks, not a running total: lift jitter or a mid-gesture wiggle can shrink
                    // the current run, but a swipe that already traveled far enough still counts.
                    if (available.y < 0) peakUp = minOf(peakUp, peakUp + available.y) else peakDown = maxOf(peakDown, peakDown + available.y)
                }
                return Offset.Zero
            }

            fun onFingerLift() {
                if (!tracking) return
                tracking = false
                // The further excursion wins; ties and sub-threshold lifts do nothing.
                if (-peakUp >= swipeThreshold && -peakUp >= peakDown) latestUp()
                else if (peakDown >= swipeThreshold && peakDown > -peakUp) latestDown()
                peakUp = 0f
                peakDown = 0f
            }

            override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero
        }
    }
    // Finger lift, observed without consuming anything: the one place swipe actions fire, so a
    // trip to the end of a long layout never triggers anything by itself.
    Box(
        Modifier
            .fillMaxSize()
            .nestedScroll(swipes)
            .pointerInput(Unit) {
                // Observes only (consumes nothing): when every finger is up, the gesture is
                // over, which is the single moment a tracked swipe fires. Unit key: never
                // restarts, so a recomposition mid-swipe cannot swallow the lift.
                while (true) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (waitForUpOrCancellation() != null) swipes.onFingerLift()
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onOpenSettings() }) }
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
            LayoutRenderer(layout, render, Modifier.weight(1f), resetSignal = homePresses) {
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
private fun DefaultBanner(onSetDefault: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
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
