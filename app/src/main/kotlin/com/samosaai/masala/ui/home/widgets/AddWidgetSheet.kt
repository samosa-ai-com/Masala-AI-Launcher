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
    sourceExists: (String) -> Boolean = { true },
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
                val needsSource = spec.components.any {
                    it is com.samosaai.masala.core.contract.WidgetComponent.Video ||
                        it is com.samosaai.masala.core.contract.WidgetComponent.Snapshot
                }
                val missingSource = needsSource && sourceIds(spec).none(sourceExists)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(
                            spec.title ?: spec.id,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                        )
                        if (missingSource) {
                            Text(
                                stringResource(R.string.widgets_needs_source),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isPlaced(spec.id)) {
                        Text(
                            stringResource(R.string.widgets_on_home),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (missingSource) {
                        TextButton(onClick = onSettings) { Text(stringResource(R.string.widgets_setup_source)) }
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

private fun sourceIds(spec: com.samosaai.masala.core.contract.WidgetSpec): List<String> {
    fun com.samosaai.masala.core.contract.WidgetComponent.bindingKey(): String? = when (this) {
        is com.samosaai.masala.core.contract.WidgetComponent.Text -> binding?.takeIf { it.source == com.samosaai.masala.core.contract.BindingSource.STREAM_STATUS }?.key
        is com.samosaai.masala.core.contract.WidgetComponent.ListRows -> binding.takeIf { it.source == com.samosaai.masala.core.contract.BindingSource.STREAM_STATUS }?.key
        is com.samosaai.masala.core.contract.WidgetComponent.Gauge -> binding.takeIf { it.source == com.samosaai.masala.core.contract.BindingSource.STREAM_STATUS }?.key
        is com.samosaai.masala.core.contract.WidgetComponent.Progress -> binding.takeIf { it.source == com.samosaai.masala.core.contract.BindingSource.STREAM_STATUS }?.key
        is com.samosaai.masala.core.contract.WidgetComponent.Chart -> binding.takeIf { it.source == com.samosaai.masala.core.contract.BindingSource.STREAM_STATUS }?.key
        else -> null
    }
    fun walk(c: com.samosaai.masala.core.contract.WidgetComponent): List<String> = when (c) {
        is com.samosaai.masala.core.contract.WidgetComponent.Video -> listOf(c.stream.sourceId)
        is com.samosaai.masala.core.contract.WidgetComponent.Snapshot -> listOf(c.sourceId)
        is com.samosaai.masala.core.contract.WidgetComponent.Row -> c.children.flatMap(::walk)
        is com.samosaai.masala.core.contract.WidgetComponent.Column -> c.children.flatMap(::walk)
        else -> listOfNotNull(c.bindingKey())
    }
    return spec.components.flatMap(::walk).distinct()
}
