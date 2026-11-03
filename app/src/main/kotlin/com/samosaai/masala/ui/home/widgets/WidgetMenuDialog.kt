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
 * A generated widget's menu on home (plan `docs/plans/widget-maker.md` W2): edit it with AI right
 * here, remove it from home (every placement, one undoable version), resize within its own range,
 * or open it in the manager.
 */
@Composable
fun WidgetMenuDialog(
    spec: WidgetSpec,
    pages: List<Pair<String, String>> = emptyList(),
    currentPageId: String? = null,
    /**
     * Opens the chat input prefilled for this widget. The manager's Edit/Customize used to be the
     * only way in, which meant Settings, a tab, and finding the same widget again.
     */
    onEditWithAi: (() -> Unit)? = null,
    /** "Edit with AI", or "Customize with AI" for a template (a template edit makes a copy). */
    editWithAiLabel: String = "",
    /** Opens this widget's own settings (goal, duration, reset, place, URL…). */
    onSettings: (() -> Unit)? = null,
    onRemove: () -> Unit,
    onResize: (Int) -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onMoveToPage: ((String) -> Unit)? = null,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * Set for a widget on a cell grid: Resize then shows a drag handle on the widget itself
     * instead of the rows stepper.
     */
    onResizeOnHome: (() -> Unit)? = null,
) {
    var resizing by rememberSaveable(spec.id) { mutableStateOf(false) }
    var moving by rememberSaveable(spec.id) { mutableStateOf(false) }
    var rows by rememberSaveable(spec.id) { mutableIntStateOf(spec.size.default) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.title ?: spec.id) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!resizing && !moving) {
                    // The one action the card is for: a change to *this* widget, in one tap.
                    if (onEditWithAi != null && editWithAiLabel.isNotBlank()) {
                        MenuRow(editWithAiLabel, onEditWithAi)
                    }
                    if (onSettings != null) {
                        MenuRow(stringResource(R.string.widgets_edit_settings), onSettings)
                    }
                    MenuRow(stringResource(R.string.widgets_remove_from_home), onRemove)
                    if (onResizeOnHome != null) {
                        MenuRow(stringResource(R.string.widgets_resize_on_home), onResizeOnHome)
                    } else {
                        MenuRow(stringResource(R.string.widgets_resize, spec.size.min, spec.size.max)) {
                            rows = spec.size.default
                            resizing = true
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
            }
        },
        dismissButton = {
            TextButton(onClick = { if (resizing || moving) { resizing = false; moving = false } else onDismiss() }) {
                Text(stringResource(if (resizing || moving) R.string.action_back else R.string.action_cancel))
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
