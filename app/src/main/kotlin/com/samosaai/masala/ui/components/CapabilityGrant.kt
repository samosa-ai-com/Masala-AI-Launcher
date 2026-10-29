package com.samosaai.masala.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.PermissionController
import com.samosaai.masala.R
import com.samosaai.masala.tools.Capability
import com.samosaai.masala.tools.HealthTool

/**
 * What the Grant button does for a tool's `NeedsCapability` marker: a runtime permission gets its
 * dialog, Health Connect its own request screen, and a special access (usage, notification, Do Not
 * Disturb, all-files) the system settings screen for it. All-files access first says why in one
 * line, since the system screen doesn't. [onResult] is told when a dialog is answered; a settings
 * screen can't report back, so the user comes back and taps Try again.
 */
@Composable
fun rememberCapabilityGrant(onResult: (granted: Boolean) -> Unit = {}): (marker: String) -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { onResult(it.containsAll(HealthTool.PERMISSIONS)) }
    var explaining by remember { mutableStateOf<Capability?>(null) }
    explaining?.let { capability ->
        AlertDialog(
            onDismissRequest = { explaining = null },
            title = { Text(stringResource(R.string.all_files_reason_title)) },
            text = { Text(stringResource(R.string.all_files_reason)) },
            confirmButton = {
                TextButton(onClick = {
                    explaining = null
                    capability.settingsIntent(context)?.let(context::startActivity)
                }) { Text(stringResource(R.string.all_files_reason_continue)) }
            },
            dismissButton = { TextButton(onClick = { explaining = null }) { Text(stringResource(R.string.all_files_reason_not_now)) } },
        )
    }
    return remember(context, permission, health) {
        { marker ->
            val capability = Capability.byMarker(marker)
            when {
                capability == Capability.HEALTH_CONNECT -> health.launch(HealthTool.PERMISSIONS)
                capability == Capability.ALL_FILES -> explaining = capability
                capability != null -> capability.settingsIntent(context)?.let(context::startActivity)
                marker.startsWith("android.permission.") -> permission.launch(marker)
            }
        }
    }
}
