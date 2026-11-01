package com.samosaai.masala.ui.home.widgets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.samosaai.masala.R
import com.samosaai.masala.widgets.HttpApproval

/**
 * Host approval before a web-fetching widget goes on home (plan `docs/plans/widget-maker.md`
 * §5): names each host and its cadence. One approval per widget id and host; a changed host
 * asks again.
 */
@Composable
fun HttpApprovalDialog(
    fetches: List<HttpApproval.Fetch>,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.widgets_approval_title)) },
        text = { Text(HttpApproval.describe(fetches), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onApprove) { Text(stringResource(R.string.widgets_approval_allow)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_back)) } },
    )
}
