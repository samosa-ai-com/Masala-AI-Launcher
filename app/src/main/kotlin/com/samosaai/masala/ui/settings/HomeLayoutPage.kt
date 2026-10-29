package com.samosaai.masala.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.layout.LayoutRepository
import com.samosaai.masala.masala
import com.samosaai.masala.ui.generate.GenerateActivity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * "Home layout": lock the layout, generate with AI, the saved versions with preview/rename/
 * delete, the swipe gestures, and export or import. [onExport] and [onImport] start the system
 * file pickers, which only an activity can do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeLayoutPage(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    problems: List<LayoutChecker.Problem>?,
    message: String?,
    onDismissProblems: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.masala
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by app.layouts.historyState.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }
    val skills = remember { app.skills.getPersonas() }

    SettingsScaffold(stringResource(R.string.settings_home_layout), onBack = onBack) {
        // The toggle labels itself, so this card needs no heading of its own.
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsToggleRow(stringResource(R.string.settings_lock_home), settings.homeLocked) { on -> update { it.copy(homeLocked = on) } }
                Text(
                    stringResource(R.string.settings_lock_home_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // The one call to action on the page, so it gets a filled, full-width button.
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CardHeading(stringResource(R.string.settings_generate_ai))
                Text(
                    stringResource(R.string.settings_generate_ai_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { context.startActivity(Intent(context, GenerateActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.settings_generate_ai))
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CardHeading(stringResource(R.string.settings_layout_history))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalIconButton(enabled = history.canUndo, onClick = { scope.launch { app.layouts.undo() } }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.settings_undo))
                    }
                    FilledTonalIconButton(enabled = history.canRedo, onClick = { scope.launch { app.layouts.redo() } }) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.settings_redo))
                    }
                    OutlinedButton(
                        onClick = { scope.launch { status = app.layouts.resetToClassic().firstOrNull()?.toString() } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.settings_reset_classic), maxLines = 1)
                    }
                }
                // The reset result belongs next to the button that produced it.
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                VersionsSection(
                    history = history,
                    onSelect = { id -> scope.launch { app.layouts.select(id) } },
                    onRename = { id, label -> scope.launch { app.layouts.renameVersion(id, label) } },
                    onDelete = { id -> scope.launch { app.layouts.deleteVersion(id) } },
                    onPreview = { id ->
                        context.startActivity(
                            Intent(context, PreviewActivity::class.java)
                                .putExtra(PreviewActivity.EXTRA_VERSION_ID, id)
                        )
                    },
                    onRefine = { id ->
                        context.startActivity(
                            Intent(context, GenerateActivity::class.java)
                                .putExtra(GenerateActivity.EXTRA_REVISE_VERSION_ID, id)
                        )
                    },
                )
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CardHeading(stringResource(R.string.settings_gestures))
                GesturePicker(stringResource(R.string.settings_swipe_up), settings.swipeUp, skills.map { it.id to it.title.ifBlank { it.id } }) { a -> update { it.copy(swipeUp = a) } }
                GesturePicker(stringResource(R.string.settings_swipe_down), settings.swipeDown, skills.map { it.id to it.title.ifBlank { it.id } }) { a -> update { it.copy(swipeDown = a) } }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CardHeading(stringResource(R.string.settings_layout_backup))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_export_layout)) }
                    OutlinedButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_import_layout)) }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    if (problems != null) {
        AlertDialog(
            onDismissRequest = onDismissProblems,
            title = { Text(stringResource(R.string.settings_import_rejected)) },
            text = { Text(problems.take(MAX_SHOWN).joinToString("\n") { it.toString() } + if (problems.size > MAX_SHOWN) "\n…" else "") },
            confirmButton = { TextButton(onClick = onDismissProblems) { Text(stringResource(R.string.confirm_allow)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionsSection(
    history: LayoutRepository.HistoryState,
    onSelect: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onPreview: (Long) -> Unit,
    onRefine: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<LayoutRepository.Version?>(null) }
    var deleteTarget by remember { mutableStateOf<LayoutRepository.Version?>(null) }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    val filtered = remember(history.versions, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) history.versions else history.versions.filter { it.label.lowercase().contains(q) }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(R.string.settings_version_search)) },
        supportingText = { Text(stringResource(R.string.settings_version_count, filtered.size)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (filtered.isEmpty()) {
        Text(
            stringResource(R.string.settings_version_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        // Dense rows: tapping a row previews it, the overflow holds the rest. Bounded height
        // so the outer settings column still scrolls while the list virtualizes.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
        ) {
            itemsIndexed(filtered, key = { _, v -> v.id }) { i, v ->
                if (i > 0) HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.settings_version_preview),
                        ) { onPreview(v.id) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            v.label,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            format.format(Date(v.createdAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (v.current) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.settings_version_current)) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuFor = v.id }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_options))
                        }
                        DropdownMenu(expanded = menuFor == v.id, onDismissRequest = { menuFor = null }) {
                            if (!v.current) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings_version_apply)) },
                                    leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                                    onClick = {
                                        menuFor = null
                                        onSelect(v.id)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_version_preview)) },
                                leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                                onClick = {
                                    menuFor = null
                                    onPreview(v.id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_refine_version)) },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    menuFor = null
                                    onRefine(v.id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_version_rename)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    menuFor = null
                                    renameTarget = v
                                },
                            )
                            if (history.versions.size > 1) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings_version_delete)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = {
                                        menuFor = null
                                        deleteTarget = v
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { v ->
        var name by remember(v.id) { mutableStateOf(v.label) }
        val clean = name.trim()
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.settings_version_rename_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 60) name = it },
                        label = { Text(stringResource(R.string.settings_version_name_label)) },
                        singleLine = true,
                        isError = clean.isEmpty(),
                        supportingText = if (clean.isEmpty()) {
                            { Text(stringResource(R.string.settings_version_rename_empty)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = clean.isNotEmpty() && clean != v.label, onClick = {
                    onRename(v.id, clean)
                    renameTarget = null
                }) { Text(stringResource(R.string.confirm_allow)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.action_back)) } },
        )
    }

    deleteTarget?.let { v ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.settings_version_delete_title)) },
            text = { Text(stringResource(R.string.settings_version_delete_message, v.label)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(v.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.settings_version_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_back)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GesturePicker(title: String, current: MasalaAction, skills: List<Pair<String, String>>, onPick: (MasalaAction) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        val choices = buildList {
            add(stringResource(R.string.gesture_drawer) to MasalaAction.OpenDrawer)
            add(stringResource(R.string.gesture_notifications) to MasalaAction.ExpandNotifications)
            add(stringResource(R.string.gesture_search) to MasalaAction.Search())
            add(stringResource(R.string.gesture_ask) to MasalaAction.AskMasala(""))
            skills.forEach { (id, label) -> add(label to MasalaAction.RunSkill(id)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (label, action) ->
                FilterChip(selected = current == action, onClick = { onPick(action) }, label = { Text(label) })
            }
        }
    }
}

/**
 * A heading inside a card. Unlike the page-level [SettingsSectionTitle] it carries no top offset
 * and no primary colour — the card edge already frames it, so an offset only reads as a double gap.
 */
@Composable
private fun CardHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

private const val MAX_SHOWN = 12
