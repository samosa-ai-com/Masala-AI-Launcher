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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.BuildConfig
import com.samosaai.masala.R
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.core.scheduled.ScheduledTask
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.data.ThemeMode
import com.samosaai.masala.launcher.HomeRole
import com.samosaai.masala.launcher.rememberAppIcon
import com.samosaai.masala.masala
import com.samosaai.masala.miniapps.MiniAppActivity
import com.samosaai.masala.ui.skills.SkillSheetActivity
import com.samosaai.masala.ui.components.LauncherRow
import com.samosaai.masala.ui.drawer.visibilityLabel
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.mcp.McpActivity
import com.samosaai.masala.ui.profile.ProfileActivity
import com.samosaai.masala.ui.secondbrain.SecondBrainActivity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The settings shell (plan Phase 1): home app, theme, text size, drawer, hidden apps. */
class SettingsActivity : ComponentActivity() {
    private enum class Page { MAIN, HIDDEN_APPS, LAYOUT, RECORDING, MINI_APPS, SKILLS, TASKS, FILES }

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
                            onSkills = { page = Page.SKILLS },
                            onTasks = { page = Page.TASKS },
                            onFiles = { page = Page.FILES },
                        )
                        Page.HIDDEN_APPS -> HiddenAppsPage(onBack = { page = Page.MAIN })
                        Page.LAYOUT -> LayoutPage(settings, onBack = { page = Page.MAIN })
                        Page.RECORDING -> RecordingPage(onBack = { page = Page.MAIN })
                        Page.MINI_APPS -> MiniAppsPage(onBack = { page = Page.MAIN })
                        Page.SKILLS -> SkillsPage(onBack = { page = Page.MAIN })
                        Page.TASKS -> ScheduledTasksPage(onBack = { page = Page.MAIN })
                        Page.FILES -> masala.fileIndex?.let { FilesPage(it, onBack = { page = Page.MAIN }) }
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
        onSkills: () -> Unit,
        onTasks: () -> Unit,
        onFiles: () -> Unit,
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

            // Only with :pro in the build: without it there is no index and no all-files access.
            if (masala.fileIndex != null) {
                SettingsNavRow(
                    title = stringResource(R.string.settings_files),
                    summary = stringResource(R.string.settings_files_summary),
                    onClick = onFiles,
                )
            }

            SettingsNavRow(
                title = stringResource(R.string.settings_skills),
                summary = stringResource(R.string.settings_skills_summary),
                onClick = onSkills,
            )

            SettingsNavRow(
                title = stringResource(R.string.settings_scheduled_tasks),
                summary = stringResource(R.string.settings_scheduled_tasks_summary),
                onClick = onTasks,
            )

            SettingsSectionTitle(stringResource(R.string.settings_ask))
            SettingsToggleRow(stringResource(R.string.settings_read_replies), settings.readRepliesAloud) { on ->
                update { it.copy(readRepliesAloud = on) }
            }
            SettingsNavRow(stringResource(R.string.settings_test_voice), stringResource(R.string.settings_test_voice_summary)) {
                scope.launch {
                    val reason = masala.speakReply(getString(R.string.settings_test_voice_sentence))
                    android.widget.Toast.makeText(
                        this@SettingsActivity,
                        reason ?: getString(R.string.settings_test_voice_ok),
                        if (reason == null) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            SettingsNavRow(stringResource(R.string.wiki_title), stringResource(R.string.wiki_summary)) {
                startActivity(Intent(this@SettingsActivity, SecondBrainActivity::class.java))
            }
            SettingsNavRow(stringResource(R.string.settings_profile), stringResource(R.string.settings_profile_summary)) {
                startActivity(Intent(this@SettingsActivity, ProfileActivity::class.java))
            }
            SettingsNavRow(stringResource(R.string.mcp_title), stringResource(R.string.mcp_summary)) {
                startActivity(Intent(this@SettingsActivity, McpActivity::class.java))
            }

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
                                scope.launch { masala.deleteMiniApp(row.id) }
                            }) { Text(stringResource(R.string.settings_version_delete)) }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_back)) } },
                    )
                }
            }
        }
    }

    /** Every installed skill in one place, so a chat is one tap away (plan F4). */
    @Composable
    private fun SkillsPage(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var filter by rememberSaveable { mutableStateOf("") }
        var url by rememberSaveable { mutableStateOf("") }
        var version by remember { mutableIntStateOf(0) }
        val communityIds = remember(version) { masala.skills.getCommunitySkills().map { it.id }.toSet() }
        val skills = remember(filter, version) {
            val q = filter.trim()
            if (q.isEmpty()) masala.skills.getAllSkills() else masala.skills.searchSkills(q)
        }
        SettingsScaffold(stringResource(R.string.settings_skills), onBack = onBack) {
            OutlinedTextField(
                filter, { filter = it },
                label = { Text(stringResource(R.string.skills_filter)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (skills.isEmpty()) {
                Text(stringResource(R.string.skills_empty), style = MaterialTheme.typography.bodyMedium)
            }
            skills.forEach { skill ->
                key(skill.id) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(skill.title.ifBlank { skill.id }, style = MaterialTheme.typography.titleMedium)
                    if (communityIds.contains(skill.id)) {
                        Text(stringResource(R.string.skills_community), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (skill.description.isNotBlank()) {
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            startActivity(SkillSheetActivity.intent(this@SettingsActivity, skill.id, null))
                        }) { Text(stringResource(R.string.skills_open)) }
                        if (communityIds.contains(skill.id)) {
                            var confirmDelete by remember(skill.id) { mutableStateOf(false) }
                            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.skills_delete)) }
                            if (confirmDelete) {
                                AlertDialog(
                                    onDismissRequest = { confirmDelete = false },
                                    title = { Text(stringResource(R.string.skills_delete_title)) },
                                    text = { Text(stringResource(R.string.skills_delete_message, skill.title.ifBlank { skill.id })) },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            confirmDelete = false
                                            scope.launch {
                                                if (!masala.skills.removeCommunity(skill.id)) {
                                                    android.widget.Toast.makeText(
                                                        applicationContext,
                                                        getString(R.string.skills_delete_failed),
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                                version++
                                            }
                                        }) { Text(stringResource(R.string.skills_delete)) }
                                    },
                                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_back)) } },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                } // key(skill.id)
            }
            OutlinedTextField(
                url, { url = it },
                label = { Text(stringResource(R.string.skills_import_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            var confirmImport by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { if (url.trim().isNotBlank()) confirmImport = true }) {
                Text(stringResource(R.string.skills_import))
            }
            if (confirmImport) {
                val link = url.trim()
                val host = runCatching { android.net.Uri.parse(link).host }.getOrNull().orEmpty()
                AlertDialog(
                    onDismissRequest = { confirmImport = false },
                    title = { Text(stringResource(R.string.skills_import_title)) },
                    text = { Text(stringResource(R.string.skills_import_message, host.ifBlank { link })) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmImport = false
                            scope.launch {
                                val ok = host.isNotBlank() &&
                                    runCatching { masala.skills.importCommunityFromUrl(link, setOf(host)) }.isSuccess
                                android.widget.Toast.makeText(
                                    applicationContext,
                                    getString(if (ok) R.string.skills_imported else R.string.skills_import_failed),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                if (ok) {
                                    url = ""
                                    version++
                                }
                            }
                        }) { Text(stringResource(R.string.skills_import)) }
                    },
                    dismissButton = { TextButton(onClick = { confirmImport = false }) { Text(stringResource(R.string.action_back)) } },
                )
            }
        }
    }

    /** Every scheduled task in one place, so runs can be fired, paused or deleted (plan F5). */
    @Composable
    private fun ScheduledTasksPage(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var tasks by remember { mutableStateOf(listOf<ScheduledTask>()) }
        val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
        suspend fun sync() {
            tasks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { masala.scheduledTasks.all() }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { sync() }
        fun mutate(block: suspend () -> Unit) {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    block()
                    com.samosaai.masala.scheduled.ScheduledTaskScheduler.reschedule(this@SettingsActivity)
                }
                sync()
            }
        }
        SettingsScaffold(stringResource(R.string.settings_scheduled_tasks), onBack = onBack) {
            if (tasks.isEmpty()) {
                Text(stringResource(R.string.scheduled_tasks_empty), style = MaterialTheme.typography.bodyMedium)
            }
            tasks.forEach { task ->
                key(task.id) {
                    var confirmDelete by remember(task.id) { mutableStateOf(false) }
                    // Paused tasks still show their scheduled time; a finished one-time task has nothing to pause.
                    val next = (task.retryAt ?: task.nextRunAt)?.let { format.format(java.util.Date(it)) }
                    val canPause = task.repeat.kind != com.samosaai.masala.core.scheduled.RepeatKind.NONE || task.nextRunAt != null
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(task.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (!task.enabled) {
                                next?.let { stringResource(R.string.scheduled_task_paused_next, it) }
                                    ?: stringResource(R.string.scheduled_task_paused)
                            } else {
                                next?.let { stringResource(R.string.scheduled_task_next, it) }
                                    ?: stringResource(R.string.scheduled_task_done)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        task.history.lastOrNull()?.let { last ->
                            Text(
                                stringResource(R.string.scheduled_task_last, last.status.name),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        com.samosaai.masala.scheduled.ScheduledTaskRuns(application).runNow(task.id)
                                    }
                                    sync()
                                }
                            }) { Text(stringResource(R.string.scheduled_task_run_now)) }
                            TextButton(
                                enabled = canPause,
                                onClick = {
                                    mutate {
                                        masala.scheduledTasks.update(task.id) {
                                            com.samosaai.masala.core.scheduled.ScheduledTaskPlanner.toggled(
                                                it, !it.enabled,
                                                System.currentTimeMillis(), java.time.ZoneId.systemDefault(),
                                            )
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(if (task.enabled) R.string.scheduled_task_pause else R.string.scheduled_task_resume))
                            }
                            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.scheduled_task_delete)) }
                        }
                    }
                    HorizontalDivider()
                    if (confirmDelete) {
                        AlertDialog(
                            onDismissRequest = { confirmDelete = false },
                            title = { Text(stringResource(R.string.scheduled_task_delete_title)) },
                            text = { Text(stringResource(R.string.scheduled_task_delete_message, task.name)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmDelete = false
                                    mutate { masala.scheduledTasks.delete(task.id) }
                                }) { Text(stringResource(R.string.scheduled_task_delete)) }
                            },
                            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_back)) } },
                        )
                    }
                }
            }
        }
    }

    /** Meeting-notes recording (plan Phase 3, Lane D): explicit Start/Stop for the mic service. */
    @Composable
    private fun RecordingPage(onBack: () -> Unit) {
        fun svc() = Intent(this@SettingsActivity, RecordingService::class.java)
        fun listFiles() = java.io.File(filesDir, "recordings").listFiles()
            ?.filter { it.isFile && it.name.startsWith("rec-") && it.name.endsWith(".m4a") }
            .orEmpty().sortedByDescending { it.lastModified() }
        // Reads the service's own state, so a Stop from the notification or a return to this page
        // shows what is actually happening instead of a remembered tap.
        val scope = rememberCoroutineScope()
        val running by RecordingService.recording.collectAsStateWithLifecycle()
        var entries by remember { mutableStateOf(listOf<java.io.File>()) }
        var denied by remember { mutableStateOf(false) }
        var playing by remember { mutableStateOf<String?>(null) }
        val player = remember { android.media.MediaPlayer() }
        fun releasePlayer() {
            player.setOnPreparedListener(null)
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            runCatching { player.stop() }
            runCatching { player.reset() }
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose {
                releasePlayer()
                player.release()
            }
        }
        fun refresh() {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val fresh = listFiles()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    entries = fresh
                    if (playing != null && fresh.none { it.absolutePath == playing }) playing = null
                }
            }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }
        val dateFormat = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
        @Composable
        fun sizeText(bytes: Long) = if (bytes < 1024 * 1024) {
            stringResource(R.string.recording_size_kb, bytes / 1024)
        } else {
            stringResource(R.string.recording_size_mb, bytes / (1024 * 1024))
        }
        fun togglePlay(file: java.io.File) {
            if (playing == file.absolutePath) {
                releasePlayer()
                playing = null
                return
            }
            // Async prepare keeps the tap responsive; errors and the natural end both release the row.
            // Listeners are cleared on every stop so a late callback can never start a dead player.
            val ok = runCatching {
                player.reset()
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { runCatching { it.start() } }
                player.setOnCompletionListener { playing = null }
                player.setOnErrorListener { _, _, _ ->
                    playing = null
                    runCatching { player.reset() }
                    player.setOnPreparedListener(null)
                    true
                }
                player.prepareAsync()
            }.isSuccess
            if (ok) {
                playing = file.absolutePath
            } else {
                releasePlayer()
                playing = null
                android.widget.Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.recording_play_failed),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
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
                    stringResource(R.string.recording_files, entries.size),
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
                            refresh()
                        },
                    ) { Text(stringResource(R.string.recording_stop)) }
                }
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.recording_empty), style = MaterialTheme.typography.bodyMedium)
                }
                entries.forEach { file ->
                    key(file.absolutePath) {
                        var confirmDelete by remember(file.absolutePath) { mutableStateOf(false) }
                        val isPlaying = playing == file.absolutePath
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                dateFormat.format(java.util.Date(file.lastModified())),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                sizeText(file.length()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { togglePlay(file) }) {
                                    Text(stringResource(if (isPlaying) R.string.recording_stop_play else R.string.recording_play))
                                }
                                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.skills_delete)) }
                            }
                        }
                        HorizontalDivider()
                        if (confirmDelete) {
                            AlertDialog(
                                onDismissRequest = { confirmDelete = false },
                                title = { Text(stringResource(R.string.recording_delete_title)) },
                                text = { Text(stringResource(R.string.recording_delete_message, dateFormat.format(java.util.Date(file.lastModified())))) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        confirmDelete = false
                                        if (isPlaying) {
                                            releasePlayer()
                                            playing = null
                                        }
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            file.delete()
                                            refresh()
                                        }
                                    }) { Text(stringResource(R.string.recording_delete)) }
                                },
                                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_back)) } },
                            )
                        }
                    }
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
