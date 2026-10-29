package com.samosaai.masala.ui.wiki

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.samosaai.masala.ui.settings.SettingsScaffold
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.launch

/**
 * The Second Brain screen: every entry can be read, edited and deleted, what the model has
 * proposed waits here for approval, and all entries export as one Markdown file.
 */
class WikiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) { Surface(Modifier.fillMaxSize()) { WikiScreen() } }
        }
    }

    @Composable
    private fun WikiScreen() {
        val app = masala
        val scope = rememberCoroutineScope()
        val pages by app.secondBrain.pages.collectAsStateWithLifecycle(emptyList())
        // Null: the list. A blank id is a new entry.
        var editing by remember { mutableStateOf<Editing?>(null) }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            if (uri != null) scope.launch {
                val text = app.secondBrain.exportMarkdown()
                val ok = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
                Toast.makeText(this@WikiActivity, if (ok) R.string.wiki_exported else R.string.settings_export_failed, Toast.LENGTH_SHORT).show()
            }
        }

        BackHandler(enabled = editing != null) { editing = null }
        val entry = editing
        if (entry != null) {
            EditPage(entry, onSave = { title, body ->
                scope.launch {
                    app.secondBrain.save(entry.id.ifBlank { null }, title, body)
                    editing = null
                }
            }, onDelete = { scope.launch { app.secondBrain.delete(entry.id); editing = null } }, onBack = { editing = null })
            return
        }

        SettingsScaffold(stringResource(R.string.wiki_title), onBack = { finish() }) {
            ProposalCards(app)
            Row2 {
                Button(onClick = { editing = Editing("", "", "") }) { Text(stringResource(R.string.wiki_new)) }
                OutlinedButton(enabled = pages.isNotEmpty(), onClick = { export.launch("masala-secondbrain.md") }) { Text(stringResource(R.string.wiki_export)) }
            }
            if (pages.isEmpty()) Text(stringResource(R.string.wiki_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
            // The scaffold's content already scrolls, and a brain is a handful of short entries.
            pages.forEach { p ->
                val id = app.secondBrain.idOf(p)
                val title = app.secondBrain.titleOf(p)
                Column(Modifier.fillMaxWidth().clickable { editing = Editing(id, title, app.secondBrain.bodyOf(p)) }.padding(vertical = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(app.secondBrain.previewOf(p), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    /** An entry being edited: the vault-relative id, or blank for a new entry. */
    private data class Editing(val id: String, val title: String, val body: String)

    @Composable
    private fun Row2(content: @Composable () -> Unit) {
        androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }

    @Composable
    private fun EditPage(entry: Editing, onSave: (String, String) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
        var title by rememberSaveable(entry.id) { mutableStateOf(entry.title) }
        var body by rememberSaveable(entry.id) { mutableStateOf(entry.body) }
        var confirmDelete by remember { mutableStateOf(false) }
        SettingsScaffold(stringResource(if (entry.id.isBlank()) R.string.wiki_new else R.string.wiki_edit), onBack = onBack) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.wiki_page_title)) }, singleLine = true)
            OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.wiki_page_text)) }, minLines = 6)
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
