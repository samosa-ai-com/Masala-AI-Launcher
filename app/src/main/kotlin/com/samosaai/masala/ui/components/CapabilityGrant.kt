package com.samosaai.masala.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import com.samosaai.masala.tools.Capability
import com.samosaai.masala.tools.HealthTool

/**
 * What the Grant button does for a tool's `NeedsCapability` marker: a runtime permission gets its
 * dialog, Health Connect its own request screen, and a special access (usage, notification, Do Not
 * Disturb, all-files) the system settings screen for it. [onResult] is told when a dialog is answered;
 * a settings screen can't report back, so the user comes back and taps Try again.
 */
@Composable
fun rememberCapabilityGrant(onResult: (granted: Boolean) -> Unit = {}): (marker: String) -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { onResult(it.containsAll(HealthTool.PERMISSIONS)) }
    return remember(context, permission, health) {
        { marker ->
            val capability = Capability.byMarker(marker)
            when {
                capability == Capability.HEALTH_CONNECT -> health.launch(HealthTool.PERMISSIONS)
                capability != null -> capability.settingsIntent(context)?.let(context::startActivity)
                marker.startsWith("android.permission.") -> permission.launch(marker)
            }
        }
    }
}
