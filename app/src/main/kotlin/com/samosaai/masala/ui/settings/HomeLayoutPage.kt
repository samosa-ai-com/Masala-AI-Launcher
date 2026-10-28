package com.samosaai.masala.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.masala
import com.samosaai.masala.ui.generate.GenerateActivity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * "Home layout" (plan Phase 2): lock the layout, undo and redo with the saved versions, the swipe
 * gestures, and export or import of the layout with the app overrides. [onExport] and [onImport] start
 * the system file pickers, which only an activity can do.
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
        SettingsToggleRow(stringResource(R.string.settings_lock_home), settings.homeLocked) { on -> update { it.copy(homeLocked = on) } }
        Text(stringResource(R.string.settings_lock_home_summary), style = MaterialTheme.typography.bodySmall)

        SettingsSectionTitle(stringResource(R.string.settings_generate_ai))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(context, GenerateActivity::class.java))
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.settings_generate_ai))
            }
        }
        Text(stringResource(R.string.settings_generate_ai_summary), style = MaterialTheme.typography.bodySmall)

        SettingsSectionTitle(stringResource(R.string.settings_layout_history))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
        history.versions.forEach { v ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { scope.launch { app.layouts.select(v.id) } }, enabled = !v.current) {
                    Text(v.label + if (v.current) " ✓" else "")
                }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(context, GenerateActivity::class.java)
                            .putExtra(GenerateActivity.EXTRA_REVISE_VERSION_ID, v.id)
                    )
                }) {
                    Text(stringResource(R.string.settings_refine_version))
                }
                Text(format.format(Date(v.createdAt)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
            }
        }

        SettingsSectionTitle(stringResource(R.string.settings_gestures))
        GesturePicker(stringResource(R.string.settings_swipe_up), settings.swipeUp, skills.map { it.id to it.title.ifBlank { it.id } }) { a -> update { it.copy(swipeUp = a) } }
        GesturePicker(stringResource(R.string.settings_swipe_down), settings.swipeDown, skills.map { it.id to it.title.ifBlank { it.id } }) { a -> update { it.copy(swipeDown = a) } }

        SettingsSectionTitle(stringResource(R.string.settings_layout_backup))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_export_layout)) }
            OutlinedButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_import_layout)) }
        }
        (message ?: status)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
