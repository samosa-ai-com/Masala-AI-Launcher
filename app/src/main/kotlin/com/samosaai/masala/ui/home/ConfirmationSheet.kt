package com.samosaai.masala.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.actions.ConfirmationGate

/**
 * The gate's question as a bottom sheet (plan Phase 2): what is about to happen, Allow and Cancel.
 * Swiping it away or tapping outside is a No.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationSheet(gate: ConfirmationGate) {
    val request by gate.pending.collectAsStateWithLifecycle()
    val r = request ?: return
    ModalBottomSheet(onDismissRequest = { gate.answer(false) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(if (r.destructive) R.string.confirm_destructive_title else R.string.confirm_title),
                style = MaterialTheme.typography.titleLarge,
            )
            // Capped as well, so Allow is always reachable whatever a caller puts in the summary.
            Text(r.summary, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { gate.answer(false) }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.confirm_cancel))
                }
                Button(
                    onClick = { gate.answer(true) },
                    Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = if (r.destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                ) { Text(stringResource(R.string.confirm_allow)) }
            }
        }
    }
}
