package com.samosaai.masala.ui.skills

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.samosaai.masala.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDialog(
    onReport: (reasons: List<String>, note: String) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.report_title),
) {
    val reasons = listOf(
        R.string.report_reason_wrong, R.string.report_reason_harmful, R.string.report_reason_offensive, R.string.report_reason_other,
    ).map { stringResource(it) }
    val selected = remember { mutableStateListOf<String>() }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                reasons.forEach { reason ->
                    FilterChip(
                        selected = reason in selected,
                        onClick = { if (reason in selected) selected.remove(reason) else selected.add(reason) },
                        label = { Text(reason) },
                    )
                }
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.report_note)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onReport(selected.toList(), note) }) { Text(stringResource(R.string.report_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
