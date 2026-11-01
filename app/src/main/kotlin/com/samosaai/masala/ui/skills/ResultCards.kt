package com.samosaai.masala.ui.skills

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.samosaai.masala.R
import com.samosaai.masala.masala
import com.samosaai.masala.miniapps.openMiniApp
import com.samosaai.masala.skills.SkillRunEvent
import kotlinx.coroutines.launch
import java.io.File

/** A run's result: files, audio and video open in the system viewer, mini apps in their host; installed apps come with Phase 5. */
@Composable
fun ResultCard(result: SkillRunEvent.Result, onSave: (SkillRunEvent.Result) -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(result.title, style = MaterialTheme.typography.titleSmall)
            when (result.kind) {
                "file", "audio", "video" -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { ResultFiles.open(context, result.ref) }) { Text(stringResource(R.string.result_open)) }
                    TextButton(onClick = { ResultFiles.share(context, result.ref) }) { Text(stringResource(R.string.result_share)) }
                    TextButton(onClick = { onSave(result) }) { Text(stringResource(R.string.result_save)) }
                }
                "mini_app" -> MiniAppActions(result)
                "widget" -> WidgetActions(result)
                else -> Text(stringResource(R.string.result_not_yet), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Open and Pin for a mini app the run made; a deleted one says so instead. */
@Composable
private fun MiniAppActions(result: SkillRunEvent.Result) {
    val context = LocalContext.current
    val app = context.masala
    val scope = rememberCoroutineScope()
    var exists by remember(result.ref) { mutableStateOf(true) }
    LaunchedEffect(result.ref) { exists = app.miniApps.get(result.ref) != null }
    if (!exists) {
        Text(stringResource(R.string.result_miniapp_gone), style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { context.openMiniApp(result.ref) }) { Text(stringResource(R.string.result_open)) }
        TextButton(onClick = {
            scope.launch {
                val problems = app.layouts.pinMiniApp(result.ref, result.title)
                val text = if (problems.isEmpty()) R.string.miniapp_pinned else R.string.miniapp_pin_failed
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }) { Text(stringResource(R.string.miniapp_pin)) }
    }
}

/** A made or changed widget: live preview, then Add to home / Open in manager. */
@Composable
private fun WidgetActions(result: SkillRunEvent.Result) {
    val context = LocalContext.current
    val app = context.masala
    val scope = rememberCoroutineScope()
    var spec by remember(result.ref) { mutableStateOf<com.samosaai.masala.core.contract.WidgetSpec?>(null) }
    LaunchedEffect(result.ref) { spec = app.widgetLibrary.current()[result.ref] }
    val current = spec
    if (current == null) {
        Text(stringResource(R.string.result_widget_gone), style = MaterialTheme.typography.bodySmall)
        return
    }
    val snapshot by app.contextService.snapshot.collectAsStateWithLifecycle()
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    val previewNote = stringResource(R.string.widgets_preview_note)
    val addedOn = { pageId: String -> app.getString(R.string.widgets_added_on, app.layouts.pageLabel(pageId)) }
    val addFailedNote = stringResource(R.string.widgets_add_failed)
    val goneNote = stringResource(R.string.result_widget_gone)
    val base by app.layouts.base.collectAsStateWithLifecycle()
    val isPlaced = remember(base, result.ref) {
        base?.let { com.samosaai.masala.core.layout.LayoutEdits.widgetPlacements(it, result.ref) }.orEmpty().isNotEmpty()
    }
    var approval by remember(result.ref) { mutableStateOf<List<com.samosaai.masala.widgets.HttpApproval.Fetch>?>(null) }
    approval?.let { fetches ->
        com.samosaai.masala.ui.home.widgets.HttpApprovalDialog(
            fetches = fetches,
            onApprove = {
                approval = null
                val specNow = app.widgetLibrary.current()[result.ref] ?: return@HttpApprovalDialog
                app.httpApproval.approve(result.ref, fetches.map { it.host })
                scope.launch {
                    val placed = app.layouts.placeWidget("Add to home: ${specNow.title ?: specNow.id}", specNow.id)
                    toast(if (placed.problems.isEmpty()) addedOn(placed.pageId ?: specNow.id) else addFailedNote)
                }
            },
            onDismiss = { approval = null },
        )
    }
    val source = remember(snapshot, previewNote) {
        com.samosaai.masala.ui.home.widgets.WidgetSource(
            snapshot = { snapshot },
            store = app.widgetStates,
            onAction = { toast(previewNote) },
            streams = app.streamSources,
            http = app.httpValues,
        )
    }
    com.samosaai.masala.ui.home.widgets.WidgetCard(current, rows = null, source = source)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!isPlaced) {
            TextButton(onClick = {
                val specNow = app.widgetLibrary.current()[result.ref]
                if (specNow == null) {
                    toast(goneNote)
                    return@TextButton
                }
                val pending = com.samosaai.masala.widgets.HttpApproval.needingApproval(specNow, app.httpApproval.approved(specNow.id))
                if (pending.isEmpty()) {
                    scope.launch {
                        val placed = app.layouts.placeWidget("Add to home: ${current.title ?: current.id}", current.id)
                        toast(if (placed.problems.isEmpty()) addedOn(placed.pageId ?: current.id) else addFailedNote)
                    }
                } else {
                    approval = pending
                }
            }) { Text(stringResource(R.string.miniapp_pin)) }
        }
        TextButton(onClick = {
            context.startActivity(
                Intent(context, com.samosaai.masala.ui.settings.SettingsActivity::class.java)
                    .putExtra(com.samosaai.masala.ui.settings.SettingsActivity.EXTRA_PAGE, "WIDGETS"),
            )
        }) { Text(stringResource(R.string.widgets_manage)) }
    }
}

object ResultFiles {
    fun authority(context: Context) = "${context.packageName}.files"

    private fun mime(file: File) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun uri(context: Context, path: String) = FileProvider.getUriForFile(context, authority(context), File(path))

    fun open(context: Context, path: String) = launch(context) { Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, path), mime(File(path))) }

    fun share(context: Context, path: String) = launch(context) {
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime(File(path))).putExtra(Intent.EXTRA_STREAM, uri(context, path)), null)
    }

    // The intent is built inside the try: getUriForFile throws for a file outside the FileProvider's folders.
    private fun launch(context: Context, intent: () -> Intent) {
        try {
            context.startActivity(intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            // No app for the type, or a file outside the folders the FileProvider shares.
            Toast.makeText(context, R.string.result_cant_open, Toast.LENGTH_SHORT).show()
        }
    }

    /** Copies [path] to the document the user picked in the save dialog. */
    fun saveTo(context: Context, path: String, target: android.net.Uri): Boolean = try {
        context.contentResolver.openOutputStream(target)?.use { out -> File(path).inputStream().use { it.copyTo(out) } } != null
    } catch (e: Exception) {
        false
    }
}
