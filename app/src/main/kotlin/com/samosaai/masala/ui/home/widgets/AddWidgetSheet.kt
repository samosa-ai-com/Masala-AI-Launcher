package com.samosaai.masala.ui.home.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.widgets.WidgetTemplate
import com.samosaai.masala.core.widgets.WidgetTemplates
import com.samosaai.masala.widgets.WidgetLibrary

/**
 * Home long-press → Add widget (plan `docs/plans/widget-gallery.md`): the library as a gallery —
 * a still of each widget, searchable and shelved by category — plus Describe, Manage and Settings.
 * The preview is the widget's own spec rendered with sample values, so what is shown is what gets
 * placed.
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
    onCustomise: (String) -> Unit = {},
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
    // Template blurbs and shelves by widget id: a seeded row keeps only its spec, not its shelf.
    val templates = remember {
        runCatching { WidgetTemplates.all().associateBy { it.widget.id } }.getOrDefault(emptyMap())
    }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val shelves = remember(entries, templates) {
        WidgetTemplates.categories.filter { shelf ->
            entries.any { (spec, _) -> templates[spec.id]?.category == shelf }
        }
    }
    val shown = remember(entries, templates, query, category) {
        val q = query.trim().lowercase()
        entries.filter { (spec, _) ->
            val template = templates[spec.id]
            val shelfOk = category == null || template?.category == category
            val textOk = q.isEmpty() ||
                spec.title?.lowercase()?.contains(q) == true ||
                spec.id.lowercase().contains(q) ||
                template?.name?.lowercase()?.contains(q) == true ||
                template?.description?.lowercase()?.contains(q) == true
            shelfOk && textOk
        }
    }
    // A tall sheet with the list taking the leftover height: the old fixed 480dp list did not fit
    // above the keyboard and the first card was clipped at the top.
    val listState = rememberLazyListState()
    // A new search or shelf starts at the first result: the sheet keeps its scroll position, so
    // without this the best match could open above the fold.
    LaunchedEffect(query, category) { listState.scrollToItem(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.widgets_add_sheet_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                query,
                { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.widgets_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (shelves.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = category == null,
                            onClick = { category = null },
                            label = { Text(stringResource(R.string.widgets_all)) },
                        )
                    }
                    items(shelves) { shelf ->
                        FilterChip(
                            selected = category == shelf,
                            onClick = { category = if (category == shelf) null else shelf },
                            label = { Text(shelf) },
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.widgets_empty_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shown, key = { it.spec.id }) { (spec, _) ->
                    GalleryEntry(
                        spec = spec,
                        template = templates[spec.id],
                        placed = isPlaced(spec.id),
                        missingSource = sourceIds(spec).isNotEmpty() && sourceIds(spec).none(sourceExists),
                        onAdd = {
                            val pending = com.samosaai.masala.widgets.HttpApproval.needingApproval(spec, approvalOf(spec.id))
                            if (pending.isEmpty()) onAdd(spec.id, false) else approval = spec.id to pending
                        },
                        onCustomise = { onCustomise(spec.id) },
                        onSetupSource = onSettings,
                    )
                }
                item("actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onDescribe) { Text(stringResource(R.string.widgets_describe_short)) }
                        TextButton(onClick = onManage) { Text(stringResource(R.string.widgets_manage)) }
                        TextButton(onClick = onSettings) { Text(stringResource(R.string.widgets_settings)) }
                    }
                }
            }
        }
    }
}

/** One gallery row: what it looks like, what it does, and the two things you can do with it. */
@Composable
private fun GalleryEntry(
    spec: WidgetSpec,
    template: WidgetTemplate?,
    placed: Boolean,
    missingSource: Boolean,
    onAdd: () -> Unit,
    onCustomise: () -> Unit,
    onSetupSource: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        WidgetPreview(spec, rows = spec.size.default.coerceAtMost(2))
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    template?.name ?: spec.title ?: spec.id,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                template?.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } ?: Text(
                    if (missingSource) stringResource(R.string.widgets_needs_source) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCustomise) { Text(stringResource(R.string.widgets_customize)) }
            when {
                placed -> Text(
                    stringResource(R.string.widgets_on_home),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                missingSource -> TextButton(onClick = onSetupSource) { Text(stringResource(R.string.widgets_setup_source)) }
                else -> TextButton(onClick = onAdd) { Text(stringResource(R.string.widgets_add)) }
            }
        }
    }
}

/** Every stream source a widget needs, so a nested video can't slip past [GalleryEntry]. */
private fun sourceIds(spec: WidgetSpec): List<String> {
    fun com.samosaai.masala.core.contract.WidgetComponent.bindingKey(): String? = when (this) {
        is com.samosaai.masala.core.contract.WidgetComponent.Text -> binding?.takeIf { it.source == com.samosaai.masala.core.contract.BindingSource.STREAM_STATUS }?.key
        is com.samosaai.masala.core.contract.WidgetComponent.ListRows -> binding.takeIf { it.source == com.samosaai.masala.core.contract.BindingSource.STREAM_STATUS }?.key
        is com.samosaai.masala.core.contract.WidgetComponent.Gauge -> binding.takeIf { it.source == com.samosaai.masala.core.contract.BindingSource.STREAM_STATUS }?.key
        is com.samosaai.masala.core.contract.WidgetComponent.Progress -> binding.takeIf { it.source == com.samosaai.masala.core.contract.BindingSource.STREAM_STATUS }?.key
        is com.samosaai.masala.core.contract.WidgetComponent.Chart -> binding.takeIf { it.source == com.samosaai.masala.core.contract.BindingSource.STREAM_STATUS }?.key
        is com.samosaai.masala.core.contract.WidgetComponent.Globe -> binding?.takeIf { it.source == com.samosaai.masala.core.contract.BindingSource.STREAM_STATUS }?.key
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
