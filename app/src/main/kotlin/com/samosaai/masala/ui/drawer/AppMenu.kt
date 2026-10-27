package com.samosaai.masala.ui.drawer

import android.content.pm.ShortcutInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.ProfileKind
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.launcher.AppActions
import com.samosaai.masala.launcher.IconCache
import com.samosaai.masala.launcher.rememberAppIcon
import com.samosaai.masala.launcher.rememberShortcutIcon
import com.samosaai.masala.ui.components.LauncherRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the long-press menu can ask for. The caller does the work and closes the menu. */
interface AppMenuActions {
    fun launch(entry: AppEntry)
    fun launchShortcut(shortcut: ShortcutInfo)
    fun pinShortcut(shortcut: ShortcutInfo)
    fun setPinned(entry: AppEntry, pinned: Boolean)
    fun rename(entry: AppEntry)
    fun setVisibility(entry: AppEntry, visibility: Visibility)
    fun appInfo(entry: AppEntry)
    fun uninstall(entry: AppEntry)
}

/**
 * The long-press menu (Olauncher §2.11): the app's shortcuts, pin to home, rename, the three
 * visibility levels, app info and uninstall.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppMenu(
    entry: AppEntry,
    actions: AppActions,
    icons: IconCache,
    canUninstall: Boolean,
    menu: AppMenuActions,
    onDismiss: () -> Unit,
) {
    var shortcuts by remember(entry.key) { mutableStateOf<List<ShortcutInfo>?>(null) }
    LaunchedEffect(entry.key) { shortcuts = withContext(Dispatchers.IO) { actions.shortcuts(entry.key) } }
    val canUseShortcuts = remember { actions.canUseShortcuts() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            val subtitle = listOfNotNull(
                stringResource(R.string.menu_work_badge).takeIf { entry.profile == ProfileKind.WORK },
                stringResource(R.string.menu_renamed_from, entry.originalLabel).takeIf { entry.renamed },
            ).joinToString(" · ").ifEmpty { null }
            LauncherRow(
                label = entry.label,
                icon = rememberAppIcon(icons, entry.key),
                subtitle = subtitle,
                onClick = { menu.launch(entry) },
                onLongClick = null,
                longClickLabel = null,
                textStyle = MaterialTheme.typography.titleLarge,
                iconSize = 48,
            )

            if (!canUseShortcuts) {
                Text(
                    stringResource(R.string.menu_shortcuts_need_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            shortcuts.orEmpty().forEach { shortcut ->
                val label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.id).toString()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LauncherRow(
                        label = label,
                        icon = rememberShortcutIcon(icons, shortcut),
                        onClick = { menu.launchShortcut(shortcut) },
                        onLongClick = { menu.pinShortcut(shortcut) },
                        longClickLabel = stringResource(R.string.menu_shortcut_pin, label),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        iconSize = 32,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { menu.pinShortcut(shortcut) }) { Text(stringResource(R.string.menu_pin)) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            MenuButton(stringResource(if (entry.pinned) R.string.menu_unpin else R.string.menu_pin)) {
                menu.setPinned(entry, !entry.pinned)
            }
            MenuButton(stringResource(R.string.menu_rename)) { menu.rename(entry) }

            Text(
                stringResource(R.string.menu_visibility),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp),
            )
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Visibility.entries.forEach { level ->
                    FilterChip(
                        selected = entry.visibility == level,
                        onClick = { menu.setVisibility(entry, level) },
                        label = { Text(visibilityLabel(level)) },
                    )
                }
            }

            MenuButton(stringResource(R.string.menu_app_info)) { menu.appInfo(entry) }
            if (canUninstall) MenuButton(stringResource(R.string.menu_uninstall)) { menu.uninstall(entry) }
        }
    }
}

@Composable
fun visibilityLabel(level: Visibility): String = stringResource(
    when (level) {
        Visibility.DEFAULT -> R.string.visibility_default
        Visibility.SEARCH_ONLY -> R.string.visibility_search_only
        Visibility.HIDDEN -> R.string.visibility_hidden
    },
)

@Composable
private fun MenuButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
}
