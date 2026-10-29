package com.samosaai.masala.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.samosaai.masala.audio.RecordingService
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.layout.LayoutTransfer
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.BuildConfig
import com.samosaai.masala.R
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.data.ThemeMode
import com.samosaai.masala.launcher.HomeRole
import com.samosaai.masala.launcher.rememberAppIcon
import com.samosaai.masala.masala
import com.samosaai.masala.miniapps.MiniAppActivity
import com.samosaai.masala.ui.components.LauncherRow
import com.samosaai.masala.ui.drawer.visibilityLabel
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The settings shell (plan Phase 1): home app, theme, text size, drawer, hidden apps. */
class SettingsActivity : ComponentActivity() {
    private enum class Page { MAIN, HIDDEN_APPS, LAYOUT, RECORDING, MINI_APPS }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent bars; MasalaTheme picks the icon colour from the chosen theme.
        enableEdgeToEdge(
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) {
                Surface {
                    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
                    BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
                    when (page) {
                        Page.MAIN -> MainPage(
                            settings,
                            onHiddenApps = { page = Page.HIDDEN_APPS },
                            onLayout = { page = Page.LAYOUT },
                            onRecording = { page = Page.RECORDING },
                            onMiniApps = { page = Page.MINI_APPS },
                        )
                        Page.HIDDEN_APPS -> HiddenAppsPage(onBack = { page = Page.MAIN })
                        Page.LAYOUT -> LayoutPage(settings, onBack = { page = Page.MAIN })
                        Page.RECORDING -> RecordingPage(onBack = { page = Page.MAIN })
                        Page.MINI_APPS -> MiniAppsPage(onBack = { page = Page.MAIN })
                    }
                }
            }
        }
    }

    @Composable
    private fun MainPage(
        settings: LauncherSettings,
        onHiddenApps: () -> Unit,
        onLayout: () -> Unit,
        onRecording: () -> Unit,
        onMiniApps: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val apps by masala.launcher.apps.collectAsStateWithLifecycle()
        var isDefault by remember { mutableStateOf(HomeRole.isDefault(this)) }
        LifecycleResumeEffect(Unit) {
            isDefault = HomeRole.isDefault(this@SettingsActivity)
            onPauseOrDispose {}
        }
        fun update(change: (LauncherSettings) -> LauncherSettings) {
            scope.launch { masala.settings.update(change) }
        }

        SettingsScaffold(stringResource(R.string.settings_title), onBack = ::finish) {
            SettingsSectionTitle(stringResource(R.string.settings_home_app))
            Text(
                stringResource(if (isDefault) R.string.settings_home_app_is_default else R.string.settings_home_app_not_default),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = { HomeRole.openSettings(this@SettingsActivity) }) {
                Text(stringResource(R.string.settings_home_app_choose))
            }

            SettingsSectionTitle(stringResource(R.string.settings_appearance))
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.theme == mode,
                        onClick = { update { it.copy(theme = mode) } },
                        label = {
                            Text(
                                stringResource(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> R.string.settings_theme_system
                                        ThemeMode.LIGHT -> R.string.settings_theme_light
                                        ThemeMode.DARK -> R.string.settings_theme_dark
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            // Saved when the finger lifts, so the whole screen doesn't re-layout on every step.
            var scale by remember(settings.textScale) { mutableFloatStateOf(settings.textScale) }
            val scaleLabel = stringResource(R.string.settings_text_scale, (scale * 100).roundToInt())
            Text(scaleLabel, style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = scale,
                onValueChange = { scale = it },
                onValueChangeFinished = { update { it.copy(textScale = scale) } },
                valueRange = LauncherSettings.MIN_TEXT_SCALE..LauncherSettings.MAX_TEXT_SCALE,
                steps = 5,
                modifier = Modifier.semantics { contentDescription = scaleLabel },
            )

            SettingsNavRow(
                title = stringResource(R.string.settings_home_layout),
                summary = stringResource(R.string.settings_home_layout_summary),
                onClick = onLayout,
            )

            SettingsNavRow(
                title = stringResource(R.string.settings_miniapps),
                summary = stringResource(R.string.settings_miniapps_summary),
                onClick = onMiniApps,
            )

            SettingsNavRow(
                title = stringResource(R.string.settings_recording),
                summary = stringResource(R.string.settings_recording_summary),
                onClick = onRecording,
            )

            SettingsSectionTitle(stringResource(R.string.settings_drawer))
            SettingsToggleRow(stringResource(R.string.settings_auto_launch), settings.autoLaunch) { on ->
                update { it.copy(autoLaunch = on) }
            }
            SettingsNavRow(
                title = stringResource(R.string.settings_hidden_apps),
                summary = stringResource(
                    R.string.settings_hidden_apps_summary,
                    apps.count { it.visibility == Visibility.HIDDEN },
                    apps.count { it.visibility == Visibility.SEARCH_ONLY },
                ),
                onClick = onHiddenApps,
            )

            if (BuildConfig.DEBUG) {
                // The debug screen lives in the debug source set, so it's started by name.
                SettingsNavRow(stringResource(R.string.settings_debug), stringResource(R.string.settings_debug_summary)) {
                    startActivity(Intent().setClassName(this@SettingsActivity, "com.samosaai.masala.debug.DebugActivity"))
                }
            }
        }
    }

    @Composable
    private fun LayoutPage(settings: LauncherSettings, onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        val transfer = remember { LayoutTransfer(masala.layouts, masala.database.appOverrides()) }
        var problems by remember { mutableStateOf<List<LayoutChecker.Problem>?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch {
                val text = transfer.export()
                val ok = text != null && runCatching {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }.isSuccess
                message = getString(if (ok) R.string.settings_export_done else R.string.settings_export_failed)
            }
        }
        val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                val text = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
                when (val r = if (text == null) null else transfer.import(text)) {
                    LayoutTransfer.Result.Imported -> message = getString(R.string.settings_import_done)
                    is LayoutTransfer.Result.Rejected -> problems = r.problems
                    null -> message = getString(R.string.settings_export_failed)
                }
            }
        }
        HomeLayoutPage(
            settings = settings,
            update = { change -> scope.launch { masala.settings.update(change) } },
            problems = problems,
            message = message,
            onDismissProblems = { problems = null },
            onExport = { export.launch("masala-layout.json") },
            onImport = { import.launch(arrayOf("application/json", "text/plain", "*/*")) },
            onBack = onBack,
        )
    }

    /** Every stored mini-app, so a created app is never invisible (plan: Lane D follow-ups). */
    @Composable
    private fun MiniAppsPage(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        val apps by remember { masala.miniApps.all }.collectAsStateWithLifecycle(emptyList())
        val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
        SettingsScaffold(stringResource(R.string.settings_miniapps), onBack = onBack) {
            if (apps.isEmpty()) {
                Text(stringResource(R.string.miniapps_empty), style = MaterialTheme.typography.bodyMedium)
            }
            apps.forEach { row ->
                var confirmDelete by remember(row.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        format.format(java.util.Date(row.updatedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { startActivity(Intent(this@SettingsActivity, MiniAppActivity::class.java).putExtra(MiniAppActivity.EXTRA_APP_ID, row.id)) }) {
                            Text(stringResource(R.string.miniapp_open))
                        }
                        TextButton(onClick = {
                            scope.launch {
                                val problems = masala.layouts.pinMiniApp(row.id, row.title)
                                android.widget.Toast.makeText(
                                    this@SettingsActivity,
                                    getString(if (problems.isEmpty()) R.string.miniapp_pinned else R.string.miniapp_pin_failed),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }) { Text(stringResource(R.string.miniapp_pin)) }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text(stringResource(R.string.settings_version_delete))
                        }
                    }
                }
                HorizontalDivider()
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text(stringResource(R.string.miniapp_delete_title)) },
                        text = { Text(stringResource(R.string.miniapp_delete_message, row.title)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDelete = false
                                scope.launch { masala.miniApps.delete(row.id) }
                            }) { Text(stringResource(R.string.settings_version_delete)) }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_back)) } },
                    )
                }
            }
        }
    }

    /** Meeting-notes recording (plan Phase 3, Lane D): explicit Start/Stop for the mic service. */
    @Composable
    private fun RecordingPage(onBack: () -> Unit) {
        fun svc() = Intent(this@SettingsActivity, RecordingService::class.java)
        fun countFiles() = java.io.File(filesDir, "recordings").listFiles()?.size ?: 0
        // Reads the service's own state, so a Stop from the notification or a return to this page
        // shows what is actually happening instead of a remembered tap.
        val running by RecordingService.recording.collectAsStateWithLifecycle()
        var files by remember { mutableIntStateOf(countFiles()) }
        var denied by remember { mutableStateOf(false) }
        val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ContextCompat.startForegroundService(this@SettingsActivity, svc())
            } else {
                denied = true
            }
        }
        fun start() {
            if (ContextCompat.checkSelfPermission(this@SettingsActivity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                ContextCompat.startForegroundService(this@SettingsActivity, svc())
            } else {
                askMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        SettingsScaffold(stringResource(R.string.settings_recording), onBack = onBack) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (running) R.string.recording_running else R.string.recording_idle),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.recording_files, files),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (denied) {
                    Text(
                        stringResource(R.string.recording_needs_mic),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !running, onClick = ::start) { Text(stringResource(R.string.recording_start)) }
                    OutlinedButton(
                        enabled = running,
                        onClick = {
                            stopService(svc())
                            files = countFiles()
                        },
                    ) { Text(stringResource(R.string.recording_stop)) }
                }
            }
        }
    }

    /** Apps that are hidden or search-only, with their level; picking "Drawer and search" shows them again. */
    @Composable
    private fun HiddenAppsPage(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        val apps by masala.launcher.apps.collectAsStateWithLifecycle()
        val hidden = apps.filter { it.visibility != Visibility.DEFAULT }
        SettingsScaffold(stringResource(R.string.settings_hidden_apps), onBack = onBack) {
            if (hidden.isEmpty()) Text(stringResource(R.string.settings_hidden_apps_empty))
            hidden.forEach { entry -> HiddenAppRow(entry) { level -> scope.launch { masala.launcher.setVisibility(entry.key, level) } } }
        }
    }

    @Composable
    private fun HiddenAppRow(entry: AppEntry, onVisibility: (Visibility) -> Unit) {
        LauncherRow(
            label = entry.label,
            icon = rememberAppIcon(masala.icons, entry.key),
            subtitle = visibilityLabel(entry.visibility),
            onClick = null,
            onLongClick = null,
            longClickLabel = null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Visibility.entries.forEach { level ->
                FilterChip(
                    selected = entry.visibility == level,
                    onClick = { onVisibility(level) },
                    label = { Text(visibilityLabel(level)) },
                )
            }
        }
    }
}
