package com.samosaai.masala.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import com.samosaai.masala.R
import com.samosaai.masala.launcher.HomeRole
import com.samosaai.masala.masala
import com.samosaai.masala.tools.HealthPermissionState
import com.samosaai.masala.tools.healthConnectPermissionIntent

/**
 * Settings → Privacy → Permissions: every permission and special access, grouped, with a switch each.
 * On asks for it (the system dialog, or the system screen for special access); off walks the user to
 * the screen that can take it back, since Android lets no app drop its own grants. Nothing here is
 * required: tools still ask when they first need something.
 */
@Composable
fun PermissionsSection() {
    val context = LocalContext.current
    val groups = remember { allPermissionGroups() }
    var userToggledGroups by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }

    // Re-read every row on resume, so a grant made in Android's settings shows on the way back.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeSignal by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeSignal++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The system dialog doesn't reliably pause the activity (some OEM builds float it, and an
    // already granted permission returns at once), so re-read as soon as it answers.
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumeSignal++ }
    val homeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Turned down twice, Android stops showing the dialog: fall back to its default-apps screen.
        if (!HomeRole.isDefault(context)) HomeRole.openSettings(context)
        resumeSignal++
    }

    // Health Connect reports grants only through a suspend call: refreshed on every resume.
    var healthConnectGranted by remember { mutableStateOf(HealthPermissionState.isGranted()) }
    LaunchedEffect(resumeSignal) {
        healthConnectGranted = HealthPermissionState.refresh(context)
    }

    val app = context.masala
    val settings by app.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        settings?.let { s ->
            SettingsToggleRow(
                stringResource(R.string.permissions_allow_all_default),
                s.allowAllDefault,
                summary = stringResource(R.string.permissions_allow_all_default_summary),
            ) { on -> scope.launch { app.settings.update { it.copy(allowAllDefault = on) } } }
        }
        Text(
            stringResource(R.string.permissions_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (group in groups) {
            val items = remember(resumeSignal, group) { group.items.filter { it.isRelevant(context) } }
            if (items.isEmpty()) continue
            val hasUngranted = remember(resumeSignal, group) { items.any { !it.isGranted(context) } }
            // Collapsed until the user opens it, so the section stays short inside Privacy.
            val expanded = userToggledGroups[group.name] ?: false
            val grantedCount = remember(resumeSignal, healthConnectGranted, group) { items.count { it.isGranted(context) } }
            Row(
                Modifier.fillMaxWidth()
                    .clickable { userToggledGroups = userToggledGroups + (group.name to !expanded) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (expanded) "▼ " else "▶ ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(group.name),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.permissions_group_count, grantedCount, items.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasUngranted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (item in items) {
                        // Health Connect is driven by the refreshed state above; the rest read Android directly.
                        val granted = if (item.special == Special.HealthConnect) {
                            healthConnectGranted
                        } else {
                            remember(resumeSignal) { item.isGranted(context) }
                        }
                        PermissionRow(
                            item = item,
                            granted = granted,
                            onRequestRuntime = { perms -> runtimeLauncher.launch(perms) },
                            onRequestHome = {
                                HomeRole.requestIntent(context)?.let { homeLauncher.launch(it) } ?: HomeRole.openSettings(context)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    item: PermissionItem,
    granted: Boolean,
    onRequestRuntime: (Array<String>) -> Unit,
    onRequestHome: () -> Unit,
) {
    val context = LocalContext.current
    var showHealthConnectDialog by remember { mutableStateOf(false) }
    var showRevokeDialog by remember { mutableStateOf(false) }
    val name = stringResource(item.name)

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(item.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The switch says it with a colour; this says it in words, for a screen reader too.
            Text(
                stringResource(if (granted) R.string.permissions_granted else R.string.permissions_not_granted),
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = granted,
            onCheckedChange = { checked ->
                when {
                    // Android lets no app drop its own grants; the switch can only lead to the screen
                    // that can. A dialog, not a toast: a toast naming a settings path is gone too soon.
                    !checked -> showRevokeDialog = true
                    item.androidPermission != null ->
                        onRequestRuntime((listOf(item.androidPermission) + item.extraPermissions).toTypedArray())
                    item.special == Special.DefaultHome -> onRequestHome()
                    // On some OEM builds the Health Connect screen opens and closes with no visible
                    // result, so the switch first says where to go.
                    item.special == Special.HealthConnect -> showHealthConnectDialog = true
                    item.special != null -> openSpecialAccess(context, item.special)
                }
            },
        )
    }

    if (showRevokeDialog) {
        val special = item.special != null
        AlertDialog(
            onDismissRequest = { showRevokeDialog = false },
            title = { Text(stringResource(R.string.permissions_revoke_title, name)) },
            text = { Text(stringResource(if (special) R.string.permissions_revoke_special else R.string.permissions_revoke_runtime, name)) },
            confirmButton = {
                TextButton(onClick = {
                    showRevokeDialog = false
                    if (item.special != null) openSpecialAccess(context, item.special) else openAppPermissionSettings(context)
                }) {
                    Text(stringResource(if (special) R.string.permissions_open_settings_screen else R.string.permissions_open_app_settings))
                }
            },
            dismissButton = { TextButton(onClick = { showRevokeDialog = false }) { Text(stringResource(R.string.permissions_cancel)) } },
        )
    }

    if (showHealthConnectDialog) {
        AlertDialog(
            onDismissRequest = { showHealthConnectDialog = false },
            title = { Text(stringResource(R.string.permissions_health_connect_needed)) },
            text = { Text(stringResource(R.string.permissions_health_connect_how)) },
            confirmButton = {
                TextButton(onClick = {
                    showHealthConnectDialog = false
                    openSpecialAccess(context, Special.HealthConnect)
                }) { Text(stringResource(R.string.permissions_open_health_connect)) }
            },
            dismissButton = { TextButton(onClick = { showHealthConnectDialog = false }) { Text(stringResource(R.string.permissions_not_now)) } },
        )
    }
}

/** Opens the system screen that grants (and takes back) [special]. Never crashes on an OEM quirk. */
private fun openSpecialAccess(context: Context, special: Special) {
    when (special) {
        is Special.Of -> special.capability.settingsIntent(context)?.let { intent -> runCatching { context.startActivity(intent) } }
        Special.HealthConnect -> healthConnectPermissionIntent(context)?.let { intent ->
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        Special.DefaultHome -> HomeRole.openSettings(context)
    }
}

/** Masala's page in Android's app settings, where a runtime permission can be denied again. */
private fun openAppPermissionSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
