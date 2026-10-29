package com.samosaai.masala.ui.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppSearch
import com.samosaai.masala.core.apps.ProfileKind
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.launcher.AppCatalog
import com.samosaai.masala.launcher.IconCache
import com.samosaai.masala.launcher.rememberAppIcon
import com.samosaai.masala.ui.components.LauncherRow
import kotlinx.coroutines.delay

/** Pause after the last keystroke before a composing single match auto-launches. */
private const val AUTO_LAUNCH_PAUSE_MS = 800L

/**
 * The app drawer: a search field on top, then personal apps and a Work section with its pause
 * switch. With a query, apps marked "search only" join the results; hidden apps never show.
 */
@Composable
fun Drawer(
    apps: List<AppEntry>,
    profiles: AppCatalog.Snapshot,
    icons: IconCache,
    autoLaunch: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onAsk: (String) -> Unit,
    onWorkPaused: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val text = query.text
    val composing = query.composition != null

    val results = remember(apps, text) {
        if (text.isBlank()) {
            apps.filter { it.visibility == Visibility.DEFAULT }
        } else {
            AppSearch.rank(text, apps.filter { it.visibility != Visibility.HIDDEN }) { listOf(it.label, it.originalLabel) }
        }
    }
    LaunchedEffect(text, composing, results) {
        if (!autoLaunch) return@LaunchedEffect
        AppSearch.autoLaunchTarget(text, composing, results)?.let(onLaunch) ?: run {
            // Keyboards that keep composition open while typing (Samsung autocomplete) would
            // otherwise never auto-launch: a single match that survives a short pause launches
            // too, so the keyboard never has to close first.
            if (composing && AppSearch.autoLaunchTarget(text, false, results) != null) {
                delay(AUTO_LAUNCH_PAUSE_MS)
                AppSearch.autoLaunchTarget(text, false, results)?.let(onLaunch)
            }
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val listState = rememberLazyListState()
    LaunchedEffect(text) { listState.scrollToItem(0) }    // A Surface, not just a background, so text and icons get the scheme's on-surface colour.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.drawer_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            // Enter while composing finishes the character instead (Olauncher §2.4).
                            if (composing) return@KeyboardActions
                            results.firstOrNull()?.let(onLaunch) ?: text.takeIf { it.isNotBlank() }?.let(onAsk)
                        },
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.drawer_settings)) }
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                val searching = text.isNotBlank()
                val personal = if (searching) results else results.filter { it.profile == ProfileKind.PERSONAL }
                // Search mixes profiles, so work apps are labeled there; otherwise they sit under the Work header.
            items(personal, key = { it.key.id }) { entry -> AppItem(entry, icons, profiles, searching, onLaunch, onMenu) }

                val work = profiles.work
                if (!searching && work != null) {
                    item(key = "work-header") { WorkHeader(paused = work.paused, onWorkPaused = onWorkPaused) }
                    items(results.filter { it.profile == ProfileKind.WORK }, key = { it.key.id }) { entry ->
                        AppItem(entry, icons, profiles, labelWork = false, onLaunch, onMenu)
                    }
                }
                if (searching && results.isEmpty()) {
                    item(key = "ask") {
                        Text(
                            stringResource(R.string.drawer_no_match, text.trim()),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onAsk(text.trim()) }
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppItem(
    entry: AppEntry,
    icons: IconCache,
    profiles: AppCatalog.Snapshot,
    labelWork: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    val paused = entry.profile == ProfileKind.WORK && profiles.work?.paused == true
    LauncherRow(
        label = entry.label,
        icon = rememberAppIcon(icons, entry.key),
        onClick = { onLaunch(entry) },
        onLongClick = { onMenu(entry) },
        longClickLabel = stringResource(R.string.menu_options),
        subtitle = stringResource(R.string.menu_work_badge).takeIf { labelWork && entry.profile == ProfileKind.WORK },
        dimmed = paused,
    )
}

@Composable
private fun WorkHeader(paused: Boolean, onWorkPaused: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Switch) { onWorkPaused(!paused) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.drawer_work_header),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            if (paused) Text(stringResource(R.string.drawer_work_paused), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.drawer_work_toggle), style = MaterialTheme.typography.bodySmall)
        Switch(checked = !paused, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
    }
}
