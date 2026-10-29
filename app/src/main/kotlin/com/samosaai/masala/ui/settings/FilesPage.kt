package com.samosaai.masala.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.samosaai.masala.R
import com.samosaai.masala.core.files.FileIndex
import com.samosaai.masala.core.files.IndexStatus
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.ui.components.rememberCapabilityGrant

/**
 * Settings → Files & search (plan Phase 3 Lane B): whether Masala has All files access, and the file
 * index behind `file_search`: on/off, what it holds, Index now, Clear. Everything stays on the phone.
 */
@Composable
fun FilesPage(index: FileIndex, onBack: () -> Unit) {
    var status by remember { mutableStateOf(index.status) }
    var confirmClear by remember { mutableStateOf(false) }
    DisposableEffect(index) {
        val stop = index.observe { status = it }
        onDispose { stop() }
    }
    // Back from the system screen: access may have changed, and a first grant starts the index.
    LifecycleResumeEffect(index) {
        index.refresh()
        onPauseOrDispose {}
    }
    val grant = rememberCapabilityGrant()

    SettingsScaffold(stringResource(R.string.settings_files), onBack = onBack) {
        SettingsSectionTitle(stringResource(R.string.files_access))
        val granted = status.state != IndexStatus.State.NEEDS_ACCESS
        Text(
            stringResource(if (granted) R.string.files_access_granted else R.string.files_access_missing),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!granted) {
            OutlinedButton(onClick = { grant(ToolFiles.ALL_FILES_ACCESS) }) { Text(stringResource(R.string.files_access_grant)) }
        }

        SettingsSectionTitle(stringResource(R.string.files_index))
        SettingsToggleRow(stringResource(R.string.files_index_daily), status.enabled) { index.setEnabled(it) }
        Text(statusLine(status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status.failures > 0 && status.state == IndexStatus.State.IDLE) {
            Text(stringResource(R.string.files_index_failures, status.failures), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = granted && status.state != IndexStatus.State.RUNNING,
                onClick = { index.indexNow() },
            ) { Text(stringResource(R.string.files_index_now)) }
            OutlinedButton(enabled = status.files > 0, onClick = { confirmClear = true }) { Text(stringResource(R.string.files_index_clear)) }
        }
        Text(stringResource(R.string.files_index_private), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.files_index_clear_title)) },
            text = { Text(stringResource(R.string.files_index_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    index.clear()
                }) { Text(stringResource(R.string.files_index_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_back)) } },
        )
    }
}

@Composable
private fun statusLine(s: IndexStatus): String {
    val lastRun = s.lastRunAt
    return when {
        s.state == IndexStatus.State.NEEDS_ACCESS -> stringResource(R.string.files_index_needs_access)
        s.state == IndexStatus.State.RUNNING && s.total == 0 -> stringResource(R.string.files_index_counting)
        s.state == IndexStatus.State.RUNNING -> stringResource(R.string.files_index_running, s.done, s.total)
        lastRun == null -> stringResource(R.string.files_index_never)
        else -> stringResource(
            R.string.files_index_summary, s.files, s.withText,
            if (System.currentTimeMillis() - lastRun < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.files_index_just_now)
            else DateUtils.getRelativeTimeSpanString(lastRun, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        )
    }
}
