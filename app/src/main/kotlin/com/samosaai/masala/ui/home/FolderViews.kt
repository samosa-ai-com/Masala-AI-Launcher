package com.samosaai.masala.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlin.math.roundToInt

/** A folder on home: a glass rounded square with its first four items' icons in a 2×2 grid. */
@Composable
internal fun FolderIcon(items: List<Item>, ctx: RenderContext, size: Int) {
    val shape = RoundedCornerShape(14.dp)
    val tokens = MasalaTheme.tokens
    Box(
        Modifier.size(size.dp).clip(shape).background(tokens.wallpaperGlass).border(1.dp, tokens.hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.take(4).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { child -> ItemIcon(child, appFor(child, ctx), ctx, (size * 0.4f).roundToInt()) }
                }
            }
        }
    }
}

/**
 * An open folder: its name (tap to rename) and its items in four columns. Tapping an item runs it and
 * closes the sheet; a long press offers "Move out of folder" and, for an app, its usual menu. A locked
 * home opens folders but offers no rename and no menus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSheet(
    folder: Item,
    ctx: RenderContext,
    /** False for a folder a mode added: it isn't in the saved layout, so it can't be renamed or emptied. */
    editable: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMoveOut: (Item) -> Unit,
) {
    val children = (folder.action as? MasalaAction.OpenFolder)?.items.orEmpty()
    var menuFor by remember { mutableStateOf<Item?>(null) }
    // Long press then move drags a child out; a long press that lifts without moving still opens its menu.
    val drag = remember(folder.id, editable) { HomeDragState(onto = false) }
    val childIds = remember(children) { children.map { it.id }.toSet() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val inner = remember(ctx, folder, drag, childIds) {
        RenderContext(
            icons = ctx.icons,
            apps = ctx.apps,
            clock = ctx.clock,
            locked = ctx.locked || !editable,
            onAction = { onDismiss(); ctx.onAction(it) },
            onAppMenu = { app -> menuFor = children.firstOrNull { appFor(it, ctx) == app } },
            onClockClick = ctx.onClockClick,
            onLaunchFrom = ctx.onLaunchFrom?.let { launch -> { action, from -> onDismiss(); launch(action, from) } },
            onItemMenu = { menuFor = it },
            miniAppIcon = ctx.miniAppIcon,
            drag = drag,
            movable = childIds,
            onDrop = { child, target -> if (target is DropTarget.OutOfFolder) { drag.cancel(); onMoveOut(child) } },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        // The sheet's own card is the drag's world: leaving it is how a child gets out of the folder.
        Box(
            Modifier.fillMaxWidth().onGloballyPositioned {
                val box = it.boundsInRoot().toBox2()
                drag.container = box
                drag.sectionBoxes[FOLDER_SECTION] = box
                origin = it.positionInRoot()
            },
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text(
                    folder.label,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (ctx.locked || !editable) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.folder_rename), onClick = onRename))
                        .padding(vertical = 12.dp),
                )
                if (drag.dragging != null) {
                    val outside = drag.container?.contains(drag.pointer.x, drag.pointer.y) == false
                    Text(
                        stringResource(if (outside) R.string.folder_release_to_home else R.string.folder_drag_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (outside) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                children.chunked(FOLDER_COLUMNS).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { child ->
                            Box(Modifier.weight(1f)) {
                                ItemCell(child, inner, sectionId = FOLDER_SECTION, onWallpaper = false)
                                DropdownMenu(expanded = menuFor?.id == child.id, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.folder_move_out)) },
                                        onClick = { menuFor = null; onMoveOut(child) },
                                    )
                                    appFor(child, ctx)?.let { app ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.folder_app_options)) },
                                            onClick = { menuFor = null; onDismiss(); ctx.onAppMenu(app) },
                                        )
                                    }
                                }
                            }
                        }
                        repeat(FOLDER_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            drag.dragging?.let { LiftedItem(it, drag, origin, inner) }
        }
    }
}

/** A folder's long-press menu on home: Rename and Ungroup. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderMenu(folder: Item, onRename: () -> Unit, onUngroup: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(folder.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            ListItem(
                headlineContent = { Text(stringResource(R.string.folder_rename)) },
                colors = colors,
                modifier = Modifier.clickable(role = Role.Button, onClick = onRename),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.folder_ungroup)) },
                colors = colors,
                modifier = Modifier.clickable(role = Role.Button, onClick = onUngroup),
            )
        }
    }
}

private const val FOLDER_COLUMNS = 4

/** The drag section a sheet's children register into; it never reaches a layout edit. */
private const val FOLDER_SECTION = "folder-sheet"

/** Names a folder; Save is off while the name is blank. */
@Composable
fun FolderRenameDialog(label: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    // Selected, so typing replaces the old name.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(label, selection = TextRange(0, label.length)))
    }
    val text = field.text.trim()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_rename_title)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { if (it.text.length <= LayoutChecker.MAX_FOLDER_LABEL) field = it },
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
