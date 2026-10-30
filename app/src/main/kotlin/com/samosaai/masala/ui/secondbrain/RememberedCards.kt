package com.samosaai.masala.ui.secondbrain

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.R
import kotlinx.coroutines.launch

/**
 * What the agent just wrote to the Second Brain, with Undo and Dismiss (plan `second-brain-robust.md`,
 * D1: writes apply at once). Shown on the reply card, in the skill sheet and in the Second Brain screen.
 */
@Composable
fun RememberedCards(app: MasalaApp, modifier: Modifier = Modifier) {
    val changes by app.secondBrain.remembered.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        changes.forEach { change ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val label = when (change.action) {
                        "memory_delete" -> R.string.memory_deleted
                        "memory_file_raw" -> R.string.memory_filed
                        else -> R.string.memory_remembered
                    }
                    Text(stringResource(label, change.id), style = MaterialTheme.typography.titleSmall)
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                val ok = app.secondBrain.undo(change.undoId)
                                Toast.makeText(context, if (ok) R.string.memory_undone else R.string.memory_undo_gone, Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(stringResource(R.string.memory_undo)) }
                        TextButton(onClick = { app.secondBrain.dismissRemembered(change.undoId) }) { Text(stringResource(R.string.wiki_dismiss)) }
                    }
                }
            }
        }
    }
}
