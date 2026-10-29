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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.launch

/**
 * The Second Brain screen: the vault at a glance (health, sections, file viewer),
 * a profile editor that feeds user.md, and the approval queue. Entries can be read,
 * edited and deleted; everything exports as one Markdown file.
 */
class SecondBrainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) { Surface(Modifier.fillMaxSize()) { WikiScreen() } }
        }
    }

    private data class Editing(val id: String, val title: String, val body: String)

    @Composable
    private fun WikiScreen() {
        val app = masala
        val scope = rememberCoroutineScope()
        val pages by app.secondBrain.pages.collectAsStateWithLifecycle(emptyList())
        var snapshot by remember { mutableStateOf<SecondBrainSnapshot?>(null) }
        var health by remember { mutableStateOf<BrainHealth?>(null) }
        var editing by remember { mutableStateOf<Editing?>(null) }
        var viewingId by rememberSaveable { mutableStateOf<String?>(null) }
        var showProfile by rememberSaveable { mutableStateOf(false) }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            if (uri != null) scope.launch {
                val text = app.secondBrain.exportMarkdown()
                val ok = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
                    }
                }.getOrDefault(false)
                Toast.makeText(this@SecondBrainActivity, if (ok) R.string.wiki_exported else R.string.settings_export_failed, Toast.LENGTH_SHORT).show()
            }
        }

        suspend fun reload() {
            snapshot = app.secondBrain.snapshot()
            health = app.secondBrain.health()
        }
        // The vault snapshot follows the repository: any mutation (here, a proposal card,
        // or a tool run) refreshes the sections and the health without a manual step.
        LaunchedEffect(pages) { reload() }

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
        androidx.compose.runtime.LaunchedEffect(viewingId, byId) {
            if (snapshot != null && viewingId != null && viewing == null) viewingId = null
        }
        // Back closes the topmost layer first. Declared before the early returns so it is
        // always in the composition — otherwise Back would finish the activity from the editor.
        BackHandler(enabled = editing != null || viewing != null || showProfile) {
            when {
                editing != null -> editing = null
                viewing != null -> viewingId = null
                else -> showProfile = false
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
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val fresh = app.secondBrain.get(app.secondBrain.idOf(viewing))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
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
            ProposalCards(app)
            HealthCard(health, onOpen = { viewingId = it }, onRefresh = { scope.launch { reload() } })
            SecondBrainGraph(app, snapshot, onOpenFile = { viewingId = it })
            Row2 {
                Button(onClick = { editing = Editing("", "", "") }) { Text(stringResource(R.string.wiki_new)) }
                OutlinedButton(enabled = pages.isNotEmpty(), onClick = { export.launch("masala-secondbrain.md") }) {
                    Text(stringResource(R.string.wiki_export))
                }
                OutlinedButton(onClick = { showProfile = !showProfile }) { Text(stringResource(R.string.secondbrain_profile)) }
            }
            if (showProfile) ProfileEditor(onDone = { showProfile = false; scope.launch { reload() } })

            val snap = snapshot
            if (snap != null) {
                SectionHeader(stringResource(R.string.secondbrain_roots))
                vaultFileRow(snap.soul, "SOUL.md", { viewingId = it })
                vaultFileRow(snap.memoryIndex, "MEMORY.md", { viewingId = it })
                vaultFileRow(snap.userCard, "user.md", { viewingId = it })
                if (snap.areas.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.secondbrain_areas))
                    snap.areas.forEach { fileRow(it, { viewingId = it }) }
                }
                if (snap.people.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.secondbrain_people))
                    snap.people.forEach { fileRow(it, { viewingId = it }) }
                }
                if (snap.notes.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.secondbrain_notes))
                    snap.notes.forEach { fileRow(it, { viewingId = it }) }
                }
                if (snap.raw.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.secondbrain_raw))
                    snap.raw.forEach { fileRow(it, { viewingId = it }) }
                }
            }
            if (pages.isEmpty()) {
                Text(stringResource(R.string.wiki_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
    }

    @Composable
    private fun vaultFileRow(file: BrainFile?, fallback: String, onOpen: (String) -> Unit) {
        if (file == null) return
        val app = masala
        FileRow(app.secondBrain.titleOf(file), file.name, { onOpen(app.secondBrain.idOf(file)) })
    }

    @Composable
    private fun fileRow(file: BrainFile, onOpen: (String) -> Unit) {
        val app = masala
        val id = remember(file) { app.secondBrain.idOf(file) }
        FileRow(app.secondBrain.titleOf(file), app.secondBrain.previewOf(file), { onOpen(id) })
    }

    @Composable
    private fun FileRow(title: String, subtitle: String, onOpen: () -> Unit) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
    }

    @Composable
    private fun SectionHeader(text: String) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    }

    @Composable
    private fun HealthCard(health: BrainHealth?, onOpen: (String) -> Unit, onRefresh: () -> Unit) {
        val h = health ?: return
        var expanded by rememberSaveable { mutableStateOf(false) }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (h.isClean) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (h.isClean) stringResource(R.string.secondbrain_healthy)
                        else stringResource(R.string.secondbrain_health_issues, h.issueCount),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (!h.isClean) TextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(if (expanded) R.string.secondbrain_collapse else R.string.secondbrain_expand))
                    }
                }
                if (!h.isClean && expanded) {
                    HealthGroup(stringResource(R.string.secondbrain_group_unlinked), h.unlinked, onOpen)
                    HealthGroup(
                        stringResource(R.string.secondbrain_group_dangling),
                        h.dangling.map { "${it.fromId} → ${it.target}" },
                        null,
                    )
                    HealthGroup(
                        stringResource(R.string.secondbrain_group_invalid),
                        h.invalid.map { "${it.fileId}: ${it.errors.firstOrNull().orEmpty()}" },
                        { id -> onOpen(id.substringBefore(": ")) },
                    )
                    HealthGroup(
                        stringResource(R.string.secondbrain_group_tasks),
                        (h.unknownTasks.map { "${it.fileId}: ${it.taskId}" } +
                            h.mismatchedTasks.map { "${it.fileId} ↔ ${it.taskId}" } +
                            h.staleTaskLinks.map { it.path } +
                            h.missingBacklinks.map { it.fileId }).distinct(),
                        null,
                    )
                    HealthGroup(stringResource(R.string.secondbrain_group_unscanned), h.unscanned, onOpen)
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.secondbrain_refresh)) }
                }
            }
        }
    }

    @Composable
    private fun HealthGroup(label: String, ids: List<String>, onOpen: ((String) -> Unit)?) {
        if (ids.isEmpty()) return
        Text(label, style = MaterialTheme.typography.labelMedium)
        ids.take(20).forEach { id ->
            if (onOpen == null) {
                Text(id, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpen(id) },
                )
            }
        }
        if (ids.size > 20) Text("… +${ids.size - 20}", style = MaterialTheme.typography.bodySmall)
    }

    @Composable
    private fun FileViewer(file: BrainFile, onEdit: () -> Unit, onBack: () -> Unit) {
        val app = masala
        val id = remember(file) { app.secondBrain.idOf(file) }
        val editable = remember(file) { app.secondBrain.isEditable(id) && !file.truncated }
        SettingsScaffold(app.secondBrain.titleOf(file), onBack = onBack) {
            val ext = file.name.substringAfterLast('.', "").lowercase()
            if (app.secondBrain.isText(file) && (ext == "md" || ext == "markdown" || '.' !in file.name)) {
                Markdown(if (file.content.isBlank()) "_" else file.content)
            } else if (app.secondBrain.isText(file)) {
                Text(
                    file.content.ifBlank { "_" },
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    stringResource(R.string.secondbrain_binary, file.name, file.sizeBytes / 1024),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (editable) {
                Row2 { OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.wiki_edit)) } }
            } else if (file.truncated) {
                Text(stringResource(R.string.secondbrain_too_large), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun ProfileEditor(onDone: () -> Unit) {
        val app = masala
        val scope = rememberCoroutineScope()
        // Null = not loaded yet: loading fills them once, and Back/done clears them so a
        // reopen re-reads. Typed text survives rotation because null never comes back.
        var name by rememberSaveable { mutableStateOf<String?>(null) }
        var location by rememberSaveable { mutableStateOf<String?>(null) }
        var occupation by rememberSaveable { mutableStateOf<String?>(null) }
        var background by rememberSaveable { mutableStateOf<String?>(null) }
        fun clear() {
            name = null; location = null; occupation = null; background = null
        }
        if (name == null && location == null && occupation == null && background == null) {
            LaunchedEffect(Unit) {
                val current = app.secondBrain.profile()
                name = current["name"].orEmpty()
                location = current["location"].orEmpty()
                occupation = current["occupation"].orEmpty()
                background = current["background"].orEmpty()
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.secondbrain_profile), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(name.orEmpty(), { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.secondbrain_profile_name)) }, singleLine = true)
                OutlinedTextField(location.orEmpty(), { location = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.secondbrain_profile_location)) }, singleLine = true)
                OutlinedTextField(occupation.orEmpty(), { occupation = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.secondbrain_profile_occupation)) }, singleLine = true)
                OutlinedTextField(background.orEmpty(), { background = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.secondbrain_profile_background)) }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            app.secondBrain.syncUser(
                                mapOf(
                                    "name" to name.orEmpty(), "location" to location.orEmpty(),
                                    "occupation" to occupation.orEmpty(), "background" to background.orEmpty(),
                                ),
                            )
                            clear()
                            onDone()
                        }
                    }) { Text(stringResource(R.string.secondbrain_profile_save)) }
                    TextButton(onClick = { clear(); onDone() }) { Text(stringResource(R.string.action_close)) }
                }
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
}
