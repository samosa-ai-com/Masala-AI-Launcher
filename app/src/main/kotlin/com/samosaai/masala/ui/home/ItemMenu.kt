package com.samosaai.masala.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.data.MiniAppEntity
import com.samosaai.masala.ui.theme.MasalaTheme

/** What a home tile's menu can ask for. The caller closes the sheet and does the work. */
interface ItemMenuActions {
    fun open(item: Item)
    fun rename(item: Item)
    /** Only tiles that open a mini app: an app's icon is chosen from its own menu. */
    fun changeIcon(item: Item)
    fun removeFromHome(item: Item)
    /** Opens the chat ready to be told what to change in [app]. */
    fun editWithMasala(app: MiniAppEntity)
    fun deleteApp(app: MiniAppEntity)
}

/**
 * The menu for a home tile that isn't an installed app: a mini app, a web link, "ask Masala", a
 * timer. Installed apps keep [com.samosaai.masala.ui.drawer.AppMenu], which also offers shortcuts,
 * uninstall and App info; this one is their smaller sibling, so nothing on home is menu-less.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemMenu(
    item: Item,
    miniApp: MiniAppEntity?,
    preview: @Composable () -> Unit,
    menu: ItemMenuActions,
    onDismiss: () -> Unit,
) {
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))) { preview() }
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(if (miniApp != null) R.string.menu_miniapp_subtitle else R.string.menu_on_home),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FlowRow(
                Modifier.fillMaxWidth(),
                maxItemsInEachRow = 3,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MenuTile(Icons.Rounded.OpenInNew, stringResource(R.string.menu_open_action), Modifier.weight(1f)) { menu.open(item) }
                if (miniApp != null) {
                    MenuTile(Icons.Rounded.AutoAwesome, stringResource(R.string.menu_edit_with_masala), Modifier.weight(1f)) { menu.editWithMasala(miniApp) }
                }
                MenuTile(Icons.Rounded.Edit, stringResource(R.string.menu_rename), Modifier.weight(1f)) { menu.rename(item) }
                if (miniApp != null) {
                    MenuTile(Icons.Rounded.Palette, stringResource(R.string.menu_change_icon), Modifier.weight(1f)) { menu.changeIcon(item) }
                }
                MenuTile(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.menu_remove_from_home), Modifier.weight(1f)) { menu.removeFromHome(item) }
                if (miniApp != null) {
                    MenuTile(Icons.Rounded.Delete, stringResource(R.string.menu_delete_app), Modifier.weight(1f), danger = true) { menu.deleteApp(miniApp) }
                }
            }
            Text(
                stringResource(R.string.menu_home_hint),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textLow,
            )
        }
    }
}

/** A menu tile, matching the app menu's: an icon over a two-line label. */
@Composable
private fun MenuTile(icon: ImageVector, label: String, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
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
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** Renames a tile (and the mini app behind it, when it opens one). */
@Composable
fun ItemRenameDialog(label: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(label, selection = TextRange(0, label.length)))
    }
    val text = field.text.trim()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.item_rename_title, label)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it.copy(text = it.text.take(com.samosaai.masala.core.layout.LayoutChecker.MAX_FOLDER_LABEL)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotEmpty()) onRename(text) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(text) }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
