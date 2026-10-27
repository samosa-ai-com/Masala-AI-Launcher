package com.samosaai.masala.ui.home

import android.content.pm.ShortcutInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.launcher.IconCache
import com.samosaai.masala.launcher.rememberAppIcon
import com.samosaai.masala.launcher.rememberShortcutIcon
import com.samosaai.masala.ui.components.LauncherRow
import com.samosaai.masala.ui.components.wallpaperText

/**
 * The Phase 1 home screen: pinned apps and shortcuts on the wallpaper. Phase 2 replaces the list with
 * the layout renderer; the gestures stay. Swipe up opens the drawer, a long press on empty space opens
 * settings.
 */
@Composable
fun HomeScreen(
    pinnedApps: List<AppEntry>,
    pinnedShortcuts: List<ShortcutInfo>,
    icons: IconCache,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onLaunchShortcut: (ShortcutInfo) -> Unit,
    onUnpinShortcut: (ShortcutInfo) -> Unit,
) {
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    val openDrawerLabel = stringResource(R.string.home_open_drawer_description)
    val openSettingsLabel = stringResource(R.string.home_open_settings_description)
    // The pinned list scrolls, and a scrollable keeps the drag even with nothing to scroll, so the
    // swipe up arrives here as the scroll the list couldn't use.
    val swipeUp = remember(swipeThreshold) {
        object : NestedScrollConnection {
            var pulled = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0) {
                    pulled += available.y
                    if (pulled < -swipeThreshold) {
                        pulled = 0f
                        onOpenDrawer()
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .nestedScroll(swipeUp)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -swipeThreshold) onOpenDrawer() },
                    onVerticalDrag = { _, dy -> dragged += dy },
                )
            }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onOpenSettings() }) }
            // TalkBack can't swipe or long-press empty space, so both are offered as actions.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(openDrawerLabel) { onOpenDrawer(); true },
                    CustomAccessibilityAction(openSettingsLabel) { onOpenSettings(); true },
                )
            }
            .systemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!isDefault) DefaultBanner(onSetDefault)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                pinnedApps.forEach { entry ->
                    LauncherRow(
                        label = entry.label,
                        icon = rememberAppIcon(icons, entry.key),
                        onClick = { onLaunch(entry) },
                        onLongClick = { onMenu(entry) },
                        longClickLabel = stringResource(R.string.menu_options),
                        textStyle = wallpaperText(MaterialTheme.typography.headlineSmall),
                    )
                }
                pinnedShortcuts.forEach { ShortcutRow(it, icons, onLaunchShortcut, onUnpinShortcut) }
                if (pinnedApps.isEmpty() && pinnedShortcuts.isEmpty()) {
                    Text(
                        stringResource(R.string.home_empty),
                        style = wallpaperText(MaterialTheme.typography.bodyLarge),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            Text(
                "↑  " + stringResource(R.string.home_open_drawer),
                style = wallpaperText(MaterialTheme.typography.labelLarge),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onOpenDrawer)
                    .semantics { contentDescription = openDrawerLabel }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
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
