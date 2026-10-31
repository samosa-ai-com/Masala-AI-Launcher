package com.samosaai.masala.ui.drawer

import com.samosaai.masala.ui.theme.MasalaTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.AddHome
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import android.content.pm.ShortcutInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

    val tokens = MasalaTheme.tokens
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: the app, where it is, and App info (app menu board).
            val subtitle = listOfNotNull(
                stringResource(R.string.menu_on_home).takeIf { entry.pinned },
                stringResource(R.string.menu_work_badge).takeIf { entry.profile == ProfileKind.WORK },
                stringResource(R.string.menu_renamed_from, entry.originalLabel).takeIf { entry.renamed },
            ).joinToString(" · ").ifEmpty { null }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val icon = rememberAppIcon(icons, entry.key)
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { menu.launch(entry) }) {
                    if (icon != null) Image(icon, stringResource(R.string.menu_open, entry.label), Modifier.size(48.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(entry.label, style = MaterialTheme.typography.titleLarge)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton(onClick = { menu.appInfo(entry) }, modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f))) {
                    Icon(Icons.Rounded.Info, stringResource(R.string.menu_app_info))
                }
            }

            if (!canUseShortcuts) {
                Text(stringResource(R.string.menu_shortcuts_need_default), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val list = shortcuts.orEmpty()
            if (list.isNotEmpty()) {
                val shape = RoundedCornerShape(20.dp)
                Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, tokens.hairline, shape)) {
                    list.forEach { shortcut ->
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
                            IconButton(onClick = { menu.pinShortcut(shortcut) }) {
                                Icon(Icons.Rounded.PushPin, stringResource(R.string.menu_shortcut_pin, label), tint = tokens.textMedium)
                            }
                        }
                    }
                }
            }

            // Pinned means the app is somewhere in the home layout (any grid section), so the
            // button reads as removing it rather than the vaguer "unpin".
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(if (entry.pinned) Icons.Rounded.PushPin else Icons.Rounded.AddHome, stringResource(if (entry.pinned) R.string.menu_remove_from_home else R.string.menu_pin), Modifier.weight(1f)) {
                    menu.setPinned(entry, !entry.pinned)
                }
                ActionTile(Icons.Rounded.Edit, stringResource(R.string.menu_rename), Modifier.weight(1f)) { menu.rename(entry) }
                ActionTile(Icons.Rounded.VisibilityOff, stringResource(R.string.menu_hide), Modifier.weight(1f)) { menu.setVisibility(entry, Visibility.HIDDEN) }
                if (canUninstall) ActionTile(Icons.Rounded.Delete, stringResource(R.string.menu_uninstall), Modifier.weight(1f), danger = true) { menu.uninstall(entry) }
            }

            Text(stringResource(R.string.menu_visibility), style = MaterialTheme.typography.labelLarge)
            Segmented(Visibility.entries.map { visibilityLabel(it) }, Visibility.entries.indexOf(entry.visibility)) {
                menu.setVisibility(entry, Visibility.entries[it])
            }
        }
    }
}

@Composable
private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val tokens = MasalaTheme.tokens
    val tint = if (danger) tokens.alert else tokens.textHigh
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, tokens.hairline, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
