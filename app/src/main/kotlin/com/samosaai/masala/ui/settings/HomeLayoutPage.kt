package com.samosaai.masala.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
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
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsToggleRow(stringResource(R.string.settings_lock_home), settings.homeLocked) { on -> update { it.copy(homeLocked = on) } }
                Text(stringResource(R.string.settings_lock_home_summary), style = MaterialTheme.typography.bodySmall)
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionTitle(stringResource(R.string.settings_generate_ai))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(context, GenerateActivity::class.java)) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.settings_generate_ai))
                    }
                }
                Text(stringResource(R.string.settings_generate_ai_summary), style = MaterialTheme.typography.bodySmall)
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionTitle(stringResource(R.string.settings_layout_history))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = history.canUndo, onClick = { scope.launch { app.layouts.undo() } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.settings_undo))
                    }
                    OutlinedButton(enabled = history.canRedo, onClick = { scope.launch { app.layouts.redo() } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.settings_redo))
                    }
                    OutlinedButton(onClick = { scope.launch { status = app.layouts.resetToClassic().firstOrNull()?.toString() } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.settings_reset_classic))
                    }
                }
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionTitle(stringResource(R.string.settings_gestures))
                GesturePicker(stringResource(R.string.settings_swipe_up), settings.swipeUp, skills.map { it.id to it.title.ifBlank { it.id } }) { a -> update { it.copy(swipeUp = a) } }
                GesturePicker(stringResource(R.string.settings_swipe_down), settings.swipeDown, skills.map { it.id to it.title.ifBlank { it.id } }) { a -> update { it.copy(swipeDown = a) } }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionTitle(stringResource(R.string.settings_layout_backup))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_export_layout)) }
                    OutlinedButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_import_layout)) }
                }
                (message ?: status)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.settings_version_count, filtered.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (filtered.isEmpty()) {
        Text(stringResource(R.string.settings_version_empty), style = MaterialTheme.typography.bodyMedium)
    } else {
        // Bounded height so the outer settings column still scrolls while the list virtualizes.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.id }) { v ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(v.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    format.format(Date(v.createdAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (v.current) AssistChip(onClick = {}, label = { Text(stringResource(R.string.settings_version_current)) })
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            if (!v.current) TextButton(onClick = { onSelect(v.id) }) { Text(stringResource(R.string.settings_version_apply)) }
                            TextButton(onClick = { onPreview(v.id) }) { Text(stringResource(R.string.settings_version_preview)) }
                            TextButton(onClick = { onRefine(v.id) }) { Text(stringResource(R.string.settings_refine_version)) }
                            TextButton(onClick = { renameTarget = v }) { Text(stringResource(R.string.settings_version_rename)) }
                            if (history.versions.size > 1) {
                                TextButton(onClick = { deleteTarget = v }) { Text(stringResource(R.string.settings_version_delete)) }
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
    Text(title, style = MaterialTheme.typography.bodyLarge)
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

private const val MAX_SHOWN = 12
