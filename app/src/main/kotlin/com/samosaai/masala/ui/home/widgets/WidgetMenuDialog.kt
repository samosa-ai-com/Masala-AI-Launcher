package com.samosaai.masala.ui.home.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.WidgetSpec

/**
 * A generated widget's menu on home (plan `docs/plans/widget-maker.md` W2): remove it from home
 * (every placement, one undoable version), resize within its own range, or edit it in the manager.
 */
@Composable
fun WidgetMenuDialog(
    spec: WidgetSpec,
    pages: List<Pair<String, String>> = emptyList(),
    currentPageId: String? = null,
    onRemove: () -> Unit,
    onResize: (Int) -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onMoveToPage: ((String) -> Unit)? = null,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    /** The placement's width in cells when it sits on a cell grid; null hides the Width option. */
    width: Int? = null,
    onResizeWidth: ((Int) -> Unit)? = null,
    /** Shows a drag handle on the cell itself; null hides the option. */
    onResizeOnHome: (() -> Unit)? = null,
) {
    var resizing by rememberSaveable(spec.id) { mutableStateOf(false) }
    var widening by rememberSaveable(spec.id) { mutableStateOf(false) }
    var cells by rememberSaveable(spec.id) { mutableIntStateOf(width ?: spec.size.defaultW) }
    val minW = spec.size.minW.coerceAtMost(4)
    val maxW = spec.size.maxW.coerceAtMost(4)
    var moving by rememberSaveable(spec.id) { mutableStateOf(false) }
    var rows by rememberSaveable(spec.id) { mutableIntStateOf(spec.size.default) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.title ?: spec.id) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!resizing && !moving && !widening) {
                    MenuRow(stringResource(R.string.widgets_remove_from_home), onRemove)
                    MenuRow(stringResource(R.string.widgets_resize, spec.size.min, spec.size.max)) {
                        rows = spec.size.default
                        resizing = true
                    }
                    if (onResizeOnHome != null) MenuRow(stringResource(R.string.widgets_resize_on_home), onResizeOnHome)
                    if (width != null && onResizeWidth != null && minW < maxW) {
                        MenuRow(stringResource(R.string.widgets_width, minW, maxW)) {
                            cells = width
                            widening = true
                        }
                    }
                    if (onMoveUp != null || onMoveDown != null || onMoveToPage != null) {
                        MenuRow(stringResource(R.string.widgets_move)) { moving = true }
                    }
                    MenuRow(stringResource(R.string.widgets_edit_in_manager), onEdit)
                } else if (moving) {
                    if (onMoveUp != null) MenuRow(stringResource(R.string.widgets_move_up), onMoveUp)
                    if (onMoveDown != null) MenuRow(stringResource(R.string.widgets_move_down), onMoveDown)
                    pages.filter { it.first != currentPageId }.forEach { (id, label) ->
                        if (onMoveToPage != null) MenuRow(stringResource(R.string.widgets_move_to, label)) { onMoveToPage(id) }
                    }
                } else if (widening) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(enabled = cells > minW, onClick = { cells-- }) { Text("−") }
                        Text(
                            stringResource(R.string.widgets_cells, cells),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(enabled = cells < maxW, onClick = { cells++ }) { Text("+") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(enabled = rows > spec.size.min, onClick = { rows-- }) { Text("−") }
                        Text(
                            stringResource(R.string.widgets_rows, rows),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(enabled = rows < spec.size.max, onClick = { rows++ }) { Text("+") }
                    }
                }
            }
        },
        confirmButton = {
            if (resizing) {
                TextButton(onClick = { onResize(rows) }) { Text(stringResource(R.string.widgets_save)) }
            } else if (widening) {
                TextButton(onClick = { onResizeWidth?.invoke(cells) }) { Text(stringResource(R.string.widgets_save)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (resizing || moving || widening) { resizing = false; moving = false; widening = false } else onDismiss() }) {
                Text(stringResource(if (resizing || moving || widening) R.string.action_back else R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}
