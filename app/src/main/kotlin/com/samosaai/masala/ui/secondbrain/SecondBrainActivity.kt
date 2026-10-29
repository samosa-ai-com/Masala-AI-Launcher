package com.samosaai.masala.ui.secondbrain

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.masala
import com.samosaai.masala.secondbrain.BrainFile
import com.samosaai.masala.secondbrain.BrainHealth
import com.samosaai.masala.secondbrain.SecondBrainSnapshot
import com.samosaai.masala.ui.components.Markdown
import com.samosaai.masala.ui.settings.SettingsScaffold
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Second Brain screen: what Masala remembers, as a list of sections and as
 * a memory graph. Sections expand in place with a preview; Open hands a file
 * to the viewer for reading and editing. The health dashboard shows what keeps
 * the vault from hanging together. Approvals, export and new entries stay here;
 * the profile editor lives under Settings → Profile.
 */
class SecondBrainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) { Surface(Modifier.fillMaxSize()) { SecondBrainScreen() } }
        }
    }

    private data class Editing(val id: String, val title: String, val body: String)

    @Composable
    private fun SecondBrainScreen() {
        val app = masala
        val scope = rememberCoroutineScope()
        val pages by app.secondBrain.pages.collectAsStateWithLifecycle(emptyList())
        var snapshot by remember { mutableStateOf<SecondBrainSnapshot?>(null) }
        var health by remember { mutableStateOf<BrainHealth?>(null) }
        var showGraph by rememberSaveable { mutableStateOf(false) }
        var editing by remember { mutableStateOf<Editing?>(null) }
        var viewingId by rememberSaveable { mutableStateOf<String?>(null) }

        suspend fun reload() {
            snapshot = app.secondBrain.snapshot()
            health = app.secondBrain.health()
        }
        // The vault snapshot follows the repository: any mutation (a proposal card,
        // or a tool run) refreshes the sections and the health without a manual step.
        LaunchedEffect(pages) { reload() }

        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            if (uri != null) scope.launch {
                val text = app.secondBrain.exportMarkdown()
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
                    }
                }.getOrDefault(false)
                Toast.makeText(this@SecondBrainActivity, if (ok) R.string.wiki_exported else R.string.settings_export_failed, Toast.LENGTH_SHORT).show()
            }
        }

        val byId = remember(snapshot) {
            snapshot?.let { s ->
                listOfNotNull(s.soul, s.memoryIndex, s.userCard).associateBy { app.secondBrain.idOf(it) } +
                    s.areas.associateBy { app.secondBrain.idOf(it) } +
                    s.people.associateBy { app.secondBrain.idOf(it) } +
                    s.notes.associateBy { app.secondBrain.idOf(it) } +
                    s.raw.associateBy { app.secondBrain.idOf(it) }
            }.orEmpty()
        }
        val viewing = viewingId?.let { byId[it] }
        LaunchedEffect(viewingId, byId) {
            if (snapshot != null && viewingId != null && viewing == null) viewingId = null
        }
        // Back closes the topmost layer first. Declared before the early returns so it is
        // always in the composition — otherwise Back would finish the activity from the editor.
        BackHandler(enabled = editing != null || viewing != null || showGraph) {
            when {
                editing != null -> editing = null
                viewing != null -> viewingId = null
                else -> showGraph = false
            }
        }
        val entry = editing
        if (entry != null) {
            EditPage(entry, onSave = { title, body ->
                scope.launch {
                    val saved = app.secondBrain.save(entry.id.ifBlank { null }, title, body)
                    if (saved == null) {
                        Toast.makeText(this@SecondBrainActivity, R.string.result_save_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        editing = null
                    }
                }
            }, onDelete = {
                scope.launch {
                    app.secondBrain.delete(entry.id)
                    editing = null
                }
            }, onBack = { editing = null })
            return
        }
        if (viewing != null) {
            FileViewer(viewing, onEdit = {
                scope.launch(Dispatchers.IO) {
                    val fresh = app.secondBrain.get(app.secondBrain.idOf(viewing))
                    withContext(Dispatchers.Main) {
                        if (fresh == null) {
                            viewingId = null
                        } else if (fresh.truncated) {
                            Toast.makeText(this@SecondBrainActivity, R.string.secondbrain_too_large, Toast.LENGTH_SHORT).show()
                        } else {
                            editing = Editing(app.secondBrain.idOf(fresh), app.secondBrain.titleOf(fresh), app.secondBrain.bodyOf(fresh))
                        }
                    }
                }
            }, onBack = { viewingId = null })
            return
        }

        SettingsScaffold(stringResource(R.string.wiki_title), onBack = { finish() }) {
            Text(
                stringResource(R.string.secondbrain_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val current = snapshot
            if (current == null) {
                Text(stringResource(R.string.secondbrain_loading), style = MaterialTheme.typography.bodyMedium)
                return@SettingsScaffold
            }
            if (!current.available) {
                Text(stringResource(R.string.secondbrain_unavailable), style = MaterialTheme.typography.bodyMedium)
                return@SettingsScaffold
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!showGraph) {
                    Button(onClick = {}, enabled = false) { Text(stringResource(R.string.secondbrain_tab_list)) }
                    OutlinedButton(onClick = { showGraph = true }) { Text(stringResource(R.string.secondbrain_tab_graph)) }
                } else {
                    OutlinedButton(onClick = { showGraph = false }) { Text(stringResource(R.string.secondbrain_tab_list)) }
                    Button(onClick = {}, enabled = false) { Text(stringResource(R.string.secondbrain_tab_graph)) }
                }
            }

            if (showGraph) {
                SecondBrainGraph(app, current, onOpenFile = { viewingId = it })
            } else {
                var search by remember(current) { mutableStateOf("") }
                var shown by remember(current) { mutableStateOf(current) }
                // Filtering reads full files past the preview cap: off the main
                // thread, debounced so scans start after typing pauses. A scan
                // already running is left to finish (its result is discarded);
                // scans are bounded reads, so overlap stays brief.
                LaunchedEffect(current, search) {
                    if (search.isBlank()) {
                        shown = current
                    } else {
                        delay(300)
                        shown = withContext(Dispatchers.Default) { app.secondBrain.filter(current, search) }
                    }
                }
                ProposalCards(app)
                Row2 {
                    Button(onClick = { editing = Editing("", "", "") }) { Text(stringResource(R.string.wiki_new)) }
                    OutlinedButton(enabled = pages.isNotEmpty(), onClick = { export.launch("masala-secondbrain.md") }) {
                        Text(stringResource(R.string.wiki_export))
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(stringResource(R.string.secondbrain_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (search.isNotBlank() && remember(shown) { shown.isEmpty() }) {
                    Text(
                        text = stringResource(R.string.secondbrain_no_matches, search.trim()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    return@SettingsScaffold
                }
                shown.soul?.let { BrainSection(stringResource(R.string.secondbrain_identity), it) { viewingId = it } }
                shown.memoryIndex?.let { BrainSection(stringResource(R.string.secondbrain_map), it) { viewingId = it } }
                shown.userCard?.let { BrainSection(stringResource(R.string.secondbrain_user_card), it) { viewingId = it } }
                BrainFileList(stringResource(R.string.secondbrain_areas), shown.areas) { viewingId = it }
                BrainFileList(stringResource(R.string.secondbrain_people), shown.people) { viewingId = it }
                BrainFileList(stringResource(R.string.secondbrain_notes), shown.notes) { viewingId = it }
                BrainFileList(stringResource(R.string.secondbrain_raw), shown.raw) { viewingId = it }
                // Health is vault-level, not query-level: hide it while searching
                // so its unrelated filenames never confuse a filtered view.
                if (search.isBlank()) health?.let { BrainHealthSection(health = it) }
            }
        }
    }

    @Composable
    private fun BrainSection(heading: String, file: BrainFile, onOpenFile: (String) -> Unit) {
        val app = masala
        var expanded by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = (if (expanded) "▼ " else "▶ ") + heading,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.secondbrain_collapse else R.string.secondbrain_expand)) { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
            if (expanded) {
                BrainFileBody(app, file, onOpenFile = onOpenFile, showEdit = true)
            }
        }
        HorizontalDivider(thickness = 1.dp)
    }

    @Composable
    private fun BrainFileList(heading: String, files: List<BrainFile>, onOpenFile: (String) -> Unit) {
        val app = masala
        var expanded by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = (if (expanded) "▼ " else "▶ ") + "$heading (${files.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.secondbrain_collapse else R.string.secondbrain_expand)) { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
            if (expanded) {
                if (files.isEmpty()) {
                    Text(
                        text = stringResource(R.string.secondbrain_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    files.forEach { file ->
                        var fileExpanded by remember { mutableStateOf(false) }
                        Text(
                            text = (if (fileExpanded) "▼ " else "▶ ") + app.secondBrain.titleOf(file),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth()
                                .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.secondbrain_collapse else R.string.secondbrain_expand)) { fileExpanded = !fileExpanded }
                                .padding(vertical = 2.dp),
                        )
                        if (fileExpanded) {
                            BrainFileBody(app, file, onOpenFile = onOpenFile, showEdit = true)
                        }
                    }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp)
    }

    @Composable
    private fun BrainHealthSection(health: BrainHealth) {
        var expanded by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = (if (expanded) "▼ " else "▶ ") + stringResource(
                    if (health.isClean) R.string.secondbrain_health_clean else R.string.secondbrain_health_title,
                    health.issueCount,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.secondbrain_collapse else R.string.secondbrain_expand)) { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
            if (expanded) {
                if (health.isClean) {
                    Text(
                        text = stringResource(R.string.secondbrain_health_clean_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    HealthGroup(
                        stringResource(R.string.secondbrain_health_unlinked, health.unlinked.size),
                        health.unlinked,
                    )
                    HealthGroup(
                        stringResource(R.string.secondbrain_health_dangling, health.dangling.size),
                        health.dangling.map { stringResource(R.string.secondbrain_health_dangling_line, it.fromId, it.target) },
                    )
                    HealthGroup(
                        stringResource(R.string.secondbrain_health_invalid, health.invalid.size),
                        health.invalid.map {
                            stringResource(R.string.secondbrain_health_invalid_line, it.fileId, it.errors.firstOrNull().orEmpty())
                        },
                    )
                    HealthGroup(
                        stringResource(R.string.secondbrain_health_unknown_tasks, health.unknownTasks.size),
                        health.unknownTasks.map {
                            stringResource(R.string.secondbrain_health_unknown_line, it.fileId, it.taskId)
                        },
                    )
                    HealthGroup(
                        stringResource(R.string.secondbrain_health_mismatched, health.mismatchedTasks.size),
                        health.mismatchedTasks.map {
                            stringResource(
                                R.string.secondbrain_health_mismatch_line,
                                it.fileId,
                                it.linkedPath ?: stringResource(R.string.secondbrain_health_nowhere),
                            )
                        },
                    )
                    HealthGroup(
                        stringResource(R.string.secondbrain_health_stale, health.staleTaskLinks.size),
                        health.staleTaskLinks.map {
                            stringResource(R.string.secondbrain_health_stale_line, it.taskName, it.path)
                        },
                    )
                    HealthGroup(
                        stringResource(R.string.secondbrain_health_missing_backlink, health.missingBacklinks.size),
                        health.missingBacklinks.map {
                            stringResource(
                                R.string.secondbrain_health_missing_backlink_line,
                                it.taskName,
                                it.fileId,
                            )
                        },
                    )
                    HealthGroup(
                        stringResource(R.string.secondbrain_health_unscanned, health.unscanned.size),
                        health.unscanned,
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp)
    }

    @Composable
    private fun HealthGroup(heading: String, lines: List<String>) {
        if (lines.isEmpty()) return
        Text(
            text = heading,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp),
        )
        // Cap composed rows so a huge vault cannot jank the settings column.
        val shown = lines.take(HEALTH_GROUP_LIMIT)
        shown.forEach { line ->
            Text(
                text = "• $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 1.dp),
            )
        }
        if (lines.size > shown.size) {
            Text(
                text = stringResource(R.string.secondbrain_health_more, lines.size - shown.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 1.dp),
            )
        }
    }

    @Composable
    private fun FileViewer(file: BrainFile, onEdit: () -> Unit, onBack: () -> Unit) {
        val app = masala
        val id = remember(file) { app.secondBrain.idOf(file) }
        val editable = remember(file) { app.secondBrain.isEditable(id) && !file.truncated }
        SettingsScaffold(app.secondBrain.titleOf(file), onBack = onBack) {
            if (app.secondBrain.isText(file) && file.isMarkdown) {
                Markdown(if (file.content.isBlank()) "_" else file.content)
            } else if (app.secondBrain.isText(file)) {
                Text(
                    file.content.ifBlank { "_" },
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                BrainBinaryRow(file)
            }
            if (editable) {
                Row2 { OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.wiki_edit)) } }
            } else if (file.truncated) {
                Text(stringResource(R.string.secondbrain_too_large), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun Row2(content: @Composable () -> Unit) {
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }

    @Composable
    private fun EditPage(entry: Editing, onSave: (String, String) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
        var title by rememberSaveable(entry.id) { mutableStateOf(entry.title) }
        var body by rememberSaveable(entry.id) { mutableStateOf(entry.body) }
        var confirmDelete by remember { mutableStateOf(false) }
        SettingsScaffold(stringResource(if (entry.id.isBlank()) R.string.wiki_new else R.string.wiki_edit), onBack = onBack) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.wiki_page_title)) }, singleLine = true)
            OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.wiki_page_text)) }, minLines = 6)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = title.isNotBlank() && body.isNotBlank(), onClick = { onSave(title, body) }) { Text(stringResource(R.string.wiki_save)) }
                if (entry.id.isNotBlank()) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.wiki_delete)) }
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.wiki_delete_title, entry.title)) },
                text = { Text(stringResource(R.string.wiki_delete_body)) },
                confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.wiki_delete)) } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }

    companion object {
        /** Max issue rows composed per health group. */
        const val HEALTH_GROUP_LIMIT = 50
    }
}
