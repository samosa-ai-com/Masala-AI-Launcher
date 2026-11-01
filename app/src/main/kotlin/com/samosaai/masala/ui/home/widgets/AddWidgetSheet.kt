package com.samosaai.masala.ui.home.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.widgets.WidgetLibrary

/**
 * Home long-press → Add widget (plan `docs/plans/widget-maker.md` W2): the library with an Add
 * button per widget, plus Describe (chat prefilled), Manage (the Settings page) and Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWidgetSheet(
    library: WidgetLibrary,
    approvalOf: (String) -> Set<String>,
    isPlaced: (String) -> Boolean,
    onAdd: (id: String, approved: Boolean) -> Unit,
    onDescribe: () -> Unit,
    onManage: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries by library.watch().collectAsStateWithLifecycle(emptyList())
    var approval by remember { mutableStateOf<Pair<String, List<com.samosaai.masala.widgets.HttpApproval.Fetch>>?>(null) }
    approval?.let { (id, fetches) ->
        HttpApprovalDialog(
            fetches = fetches,
            onApprove = {
                approval = null
                onAdd(id, true)
            },
            onDismiss = { approval = null },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.widgets_add_sheet_title), style = MaterialTheme.typography.titleLarge)
            entries.forEach { (spec, _) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        spec.title ?: spec.id,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (isPlaced(spec.id)) {
                        Text(
                            stringResource(R.string.widgets_on_home),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TextButton(onClick = {
                            val pending = com.samosaai.masala.widgets.HttpApproval.needingApproval(spec, approvalOf(spec.id))
                            if (pending.isEmpty()) onAdd(spec.id, false) else approval = spec.id to pending
                        }) { Text(stringResource(R.string.widgets_add)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDescribe) { Text(stringResource(R.string.widgets_describe_short)) }
                TextButton(onClick = onManage) { Text(stringResource(R.string.widgets_manage)) }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.widgets_settings)) }
            }
        }
    }
}
