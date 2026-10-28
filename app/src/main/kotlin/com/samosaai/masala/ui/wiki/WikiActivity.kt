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
import com.samosaai.masala.data.WikiPageEntity
import com.samosaai.masala.masala
import com.samosaai.masala.ui.settings.SettingsScaffold
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.launch

/**
 * The Personal Wiki screen (arch §3.5): every page can be read, edited and deleted, what the model has
 * proposed waits here for approval, and all pages export as one Markdown file.
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
        val pages by app.wiki.pages.collectAsStateWithLifecycle(emptyList())
        // Null: the list. A page with a blank id is a new one.
        var editing by remember { mutableStateOf<WikiPageEntity?>(null) }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            if (uri != null) scope.launch {
                val text = app.wiki.exportMarkdown()
                val ok = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
                Toast.makeText(this@WikiActivity, if (ok) R.string.wiki_exported else R.string.settings_export_failed, Toast.LENGTH_SHORT).show()
            }
        }

        BackHandler(enabled = editing != null) { editing = null }
        val page = editing
        if (page != null) {
            EditPage(page, onSave = { title, body ->
                scope.launch {
                    app.wiki.save(page.id, title, body, page.source.takeIf { page.id.isNotBlank() } ?: "user")
                    editing = null
                }
            }, onDelete = { scope.launch { app.wiki.delete(page.id); editing = null } }, onBack = { editing = null })
            return
        }

        SettingsScaffold(stringResource(R.string.wiki_title), onBack = { finish() }) {
            ProposalCards(app)
            Row2 {
                Button(onClick = { editing = WikiPageEntity("", "", "", "user", 0) }) { Text(stringResource(R.string.wiki_new)) }
                OutlinedButton(enabled = pages.isNotEmpty(), onClick = { export.launch("masala-wiki.md") }) { Text(stringResource(R.string.wiki_export)) }
            }
            if (pages.isEmpty()) Text(stringResource(R.string.wiki_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
            // The scaffold's content already scrolls, and a wiki is a handful of short pages.
            pages.forEach { p ->
                Column(Modifier.fillMaxWidth().clickable { editing = p }.padding(vertical = 10.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium)
                    Text(p.markdown.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun Row2(content: @Composable () -> Unit) {
        androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }

    @Composable
    private fun EditPage(page: WikiPageEntity, onSave: (String, String) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
        var title by rememberSaveable(page.id) { mutableStateOf(page.title) }
        var body by rememberSaveable(page.id) { mutableStateOf(page.markdown) }
        var confirmDelete by remember { mutableStateOf(false) }
        SettingsScaffold(stringResource(if (page.id.isBlank()) R.string.wiki_new else R.string.wiki_edit), onBack = onBack) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.wiki_page_title)) }, singleLine = true)
            OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.wiki_page_text)) }, minLines = 6)
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = title.isNotBlank() && body.isNotBlank(), onClick = { onSave(title, body) }) { Text(stringResource(R.string.wiki_save)) }
                if (page.id.isNotBlank()) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.wiki_delete)) }
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.wiki_delete_title, page.title)) },
                text = { Text(stringResource(R.string.wiki_delete_body)) },
                confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.wiki_delete)) } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
}
