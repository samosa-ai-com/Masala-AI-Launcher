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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.samosaai.masala.R
import com.samosaai.masala.skills.SkillRunEvent
import java.io.File

/** A run's result: files, audio and video open in the system viewer; mini and installed apps come with their lanes. */
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
                else -> Text(stringResource(R.string.result_not_yet), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

object ResultFiles {
    fun authority(context: Context) = "${context.packageName}.files"

    private fun mime(file: File) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun uri(context: Context, path: String) = FileProvider.getUriForFile(context, authority(context), File(path))

    fun open(context: Context, path: String) = launch(context, Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, path), mime(File(path))))

    fun share(context: Context, path: String) {
        val send = Intent(Intent.ACTION_SEND).setType(mime(File(path))).putExtra(Intent.EXTRA_STREAM, uri(context, path))
        launch(context, Intent.createChooser(send, null))
    }

    private fun launch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
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
