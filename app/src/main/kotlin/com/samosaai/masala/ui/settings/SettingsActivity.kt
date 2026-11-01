package com.samosaai.masala.ui.settings

import com.samosaai.masala.ui.theme.MasalaColors
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.Icons
import androidx.lifecycle.lifecycleScope
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
import com.samosaai.masala.core.layout.LayoutEdits
import com.samosaai.masala.ui.home.widgets.WidgetCard
import com.samosaai.masala.ui.home.widgets.WidgetSource
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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.samosaai.masala.data.HiddenPin
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.data.ThemeMode
import com.samosaai.masala.launcher.HomeRole
import com.samosaai.masala.launcher.rememberAppIcon
import com.samosaai.masala.masala
import com.samosaai.masala.miniapps.MiniAppActivity
import com.samosaai.masala.miniapps.MiniAppIconView
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
    private enum class Page { MAIN, HIDDEN_APPS, LAYOUT, RECORDING, MINI_APPS, SKILLS, PERSONAS, TASKS, FILES, AI_MODELS, VOICE, APPEARANCE, DRAWER, PRIVACY, ABOUT, BRIEF, WIDGETS }

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
                    // Search can open a page directly (UI refresh U3); Back then leaves settings.
                    val start = remember { intent.getStringExtra(EXTRA_PAGE)?.let { n -> Page.entries.firstOrNull { it.name == n } } }
                    var page by rememberSaveable { mutableStateOf(start ?: Page.MAIN) }
                    // Set when Skills sends the user to one expert; the experts page opens its editor
                    // once and clears it, and Back returns to wherever the user came from.
                    var editExpertId by rememberSaveable { mutableStateOf<String?>(null) }
                    var personasReturn by rememberSaveable { mutableStateOf(Page.MAIN) }
                    BackHandler(enabled = page != Page.MAIN) {
                        if (start != null && page == start) finish()
                        else page = if (page == Page.PERSONAS) personasReturn else Page.MAIN
                    }
                    // Each page keeps its scroll position (and other saved state) while another page is shown.
                    val pageStates = rememberSaveableStateHolder()
                    pageStates.SaveableStateProvider(page.name) { when (page) {
                        Page.MAIN -> MainPage(
                            settings,
                            onHiddenApps = { page = Page.HIDDEN_APPS },
                            onLayout = { page = Page.LAYOUT },
                            onRecording = { page = Page.RECORDING },
                            onMiniApps = { page = Page.MINI_APPS },
                            onWidgets = { page = Page.WIDGETS },
                            onSkills = { page = Page.SKILLS },
                            onTasks = { page = Page.TASKS },
                            onFiles = { page = Page.FILES },
                            onAiModels = { page = Page.AI_MODELS },
                            onVoice = { page = Page.VOICE },
                            onPersonas = { editExpertId = null; personasReturn = Page.MAIN; page = Page.PERSONAS },
                            onPage = { page = it },
                        )
                        Page.HIDDEN_APPS -> HiddenAppsPage(onBack = { page = Page.MAIN })
                        Page.LAYOUT -> LayoutPage(settings, onBack = { page = Page.MAIN })
                        Page.RECORDING -> RecordingPage(onBack = { page = Page.MAIN })
                        Page.MINI_APPS -> MiniAppsPage(onBack = { page = Page.MAIN })
                        Page.SKILLS -> SkillsPage(onBack = { page = Page.MAIN }, onEditExpert = { id ->
                            editExpertId = id
                            personasReturn = Page.SKILLS
                            page = Page.PERSONAS
                        })
                        Page.PERSONAS -> PersonasPage(
                            onBack = { page = personasReturn; editExpertId = null },
                            initialEditId = editExpertId,
                            onConsumed = { editExpertId = null },
                        )
                        Page.TASKS -> ScheduledTasksPage(onBack = { page = Page.MAIN }, onAskInChat = { askInChat(it) })
                        Page.FILES -> masala.fileIndex?.let { FilesPage(it, onBack = { page = Page.MAIN }) }
                        Page.AI_MODELS -> AiModelsPage(onBack = { page = Page.MAIN })
                        Page.VOICE -> VoicePage(settings, update = { change -> lifecycleScope.launch { masala.settings.update(change) } }, onBack = { page = Page.MAIN })
                        Page.APPEARANCE -> AppearancePage(settings, update = { change -> lifecycleScope.launch { masala.settings.update(change) } }, onBack = { page = Page.MAIN })
                        Page.DRAWER -> DrawerPage(settings, onHiddenApps = { page = Page.HIDDEN_APPS }, onBack = { page = Page.MAIN })
                        Page.PRIVACY -> PrivacyPage(onBack = { page = Page.MAIN })
                        Page.ABOUT -> AboutPage(onBack = { page = Page.MAIN })
                        Page.BRIEF -> BriefPage(settings, update = { change -> lifecycleScope.launch { masala.settings.update(change) } }, onBack = { page = Page.MAIN })
                        Page.WIDGETS -> WidgetsPage(onBack = { page = Page.MAIN })
                    } }
                }
            }
        }
    }

    /** One row of the grouped settings home: searchable by its title and summary. */
    private class Entry(val group: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tint: androidx.compose.ui.graphics.Color?, val title: String, val summary: String, val open: () -> Unit)

    /**
     * The settings home (plan UI refresh U5, settings board): a status card, a search field, and the
     * pages grouped under Masala AI, Look & feel, Automation, Privacy & data, Pro and About.
     */
    @Composable
    private fun MainPage(
        settings: LauncherSettings,
        onHiddenApps: () -> Unit,
        onLayout: () -> Unit,
        onRecording: () -> Unit,
        onMiniApps: () -> Unit,
        onWidgets: () -> Unit,
        onSkills: () -> Unit,
        onTasks: () -> Unit,
        onFiles: () -> Unit,
        onAiModels: () -> Unit,
        onVoice: () -> Unit,
        onPersonas: () -> Unit,
        onPage: (Page) -> Unit,
    ) {
        var isDefault by remember { mutableStateOf(HomeRole.isDefault(this)) }
        LifecycleResumeEffect(Unit) {
            isDefault = HomeRole.isDefault(this@SettingsActivity)
            onPauseOrDispose {}
        }
        val profile by masala.aiSettings.profile.collectAsStateWithLifecycle()
        val hiddenApps by masala.launcher.apps.collectAsStateWithLifecycle()
        val tokens = com.samosaai.masala.ui.theme.MasalaTheme.tokens
        val ai = MasalaColors.Gold
        val voice = MasalaColors.Indigo
        val look = MasalaColors.Lilac
        val auto = MasalaColors.Chili
        val data = MasalaColors.Cardamom
        val entries = buildList {
            add(Entry(0, Icons.Rounded.Key, ai, stringResource(R.string.ai_models_title), "${profile.chat.preset.name} · ${profile.chat.model}", onAiModels))
            add(Entry(0, Icons.Rounded.Mic, voice, stringResource(R.string.voice_title), "${profile.voiceIn?.preset?.name ?: stringResource(R.string.voice_on_phone)} · ${stringResource(R.string.voice_tap)}", onVoice))
            add(Entry(0, Icons.Rounded.Person, ai, stringResource(R.string.settings_profile), stringResource(R.string.settings_profile_summary)) { startActivity(Intent(this@SettingsActivity, ProfileActivity::class.java)) })
            add(Entry(1, Icons.Rounded.Palette, look, stringResource(R.string.appearance_title), stringResource(R.string.appearance_summary)) { onPage(Page.APPEARANCE) })
            add(Entry(1, Icons.Rounded.GridView, data, stringResource(R.string.settings_home_layout), stringResource(R.string.settings_home_layout_summary), onLayout))
            add(Entry(1, Icons.Rounded.Search, data, stringResource(R.string.drawer_page_title), stringResource(R.string.drawer_page_summary)) { onPage(Page.DRAWER) })
            add(Entry(2, Icons.Rounded.AutoFixHigh, auto, stringResource(R.string.settings_skills), stringResource(R.string.settings_skills_summary), onSkills))
            add(Entry(2, Icons.Rounded.Person, auto, stringResource(R.string.personas_title), stringResource(R.string.personas_summary), onPersonas))
            add(Entry(2, Icons.Rounded.Schedule, auto, stringResource(R.string.settings_scheduled_tasks), stringResource(R.string.settings_scheduled_tasks_summary), onTasks))
            add(Entry(2, Icons.Rounded.WbSunny, auto, stringResource(R.string.brief_title), stringResource(R.string.brief_summary)) { onPage(Page.BRIEF) })
            add(Entry(2, Icons.Rounded.Extension, auto, stringResource(R.string.mcp_title), stringResource(R.string.mcp_summary)) { startActivity(Intent(this@SettingsActivity, McpActivity::class.java)) })
            add(Entry(2, Icons.Rounded.Apps, auto, stringResource(R.string.settings_miniapps), stringResource(R.string.settings_miniapps_summary), onMiniApps))
            add(Entry(1, Icons.Rounded.Widgets, look, stringResource(R.string.settings_widgets), stringResource(R.string.settings_widgets_summary), onWidgets))
            add(Entry(3, Icons.Rounded.MenuBook, data, stringResource(R.string.wiki_title), stringResource(R.string.wiki_summary)) { startActivity(Intent(this@SettingsActivity, SecondBrainActivity::class.java)) })
            add(Entry(3, Icons.Rounded.Shield, data, stringResource(R.string.privacy_title), stringResource(R.string.privacy_summary)) { onPage(Page.PRIVACY) })
            add(
                Entry(
                    3, Icons.Rounded.VisibilityOff, data,
                    stringResource(R.string.settings_hidden_apps),
                    stringResource(
                        R.string.settings_hidden_apps_summary,
                        hiddenApps.count { it.visibility == Visibility.HIDDEN },
                        hiddenApps.count { it.visibility == Visibility.SEARCH_ONLY },
                    ),
                    onHiddenApps,
                ),
            )
            // Only with :pro in the build: without it there is no index and no all-files access.
            if (masala.fileIndex != null) add(Entry(4, Icons.Rounded.Folder, ai, stringResource(R.string.settings_files), stringResource(R.string.settings_files_summary), onFiles))
            add(Entry(4, Icons.Rounded.FiberManualRecord, auto, stringResource(R.string.settings_recording), stringResource(R.string.settings_recording_summary), onRecording))
            add(Entry(5, Icons.Rounded.Info, null, stringResource(R.string.about_title), stringResource(R.string.about_summary)) { onPage(Page.ABOUT) })
            if (BuildConfig.DEBUG) {
                // The debug screen lives in the debug source set, so it's started by name.
                add(Entry(5, Icons.Rounded.BugReport, null, stringResource(R.string.settings_debug), stringResource(R.string.settings_debug_summary)) {
                    startActivity(Intent().setClassName(this@SettingsActivity, "com.samosaai.masala.debug.DebugActivity"))
                })
            }
        }
        val groups = listOf(R.string.settings_group_ai, R.string.settings_group_look, R.string.settings_group_automation, R.string.settings_group_privacy, R.string.settings_group_pro, R.string.settings_group_about).map { stringResource(it) }
        var query by rememberSaveable { mutableStateOf("") }

        SettingsScaffold(stringResource(R.string.settings_title), onBack = ::finish, close = true) {
            // Settings search: titles and summaries of every row.
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text(stringResource(R.string.settings_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isBlank()) {
                // Status: home app, and the chat model in use.
                com.samosaai.masala.ui.components.GlassSurface(
                    fill = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = { HomeRole.openSettings(this@SettingsActivity) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        com.samosaai.masala.ui.character.MasalaCharacter(settings.character, com.samosaai.masala.ui.character.CharacterState.HELLO, Modifier.size(46.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(if (isDefault) R.string.settings_home_app_is_default else R.string.settings_home_app_not_default), style = MaterialTheme.typography.titleSmall)
                            Text("${profile.chat.preset.name} · ${profile.chat.model}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val q = query.trim().lowercase()
            val shown = entries.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.summary.lowercase().contains(q) }
            if (shown.isEmpty()) Text(stringResource(R.string.settings_search_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            shown.groupBy { it.group }.toSortedMap().forEach { (g, rows) ->
                com.samosaai.masala.ui.components.SectionCard(title = groups[g]) {
                    rows.forEach { r ->
                        com.samosaai.masala.ui.components.SettingsRow(r.icon, r.title, summary = r.summary, tint = r.tint, onClick = r.open) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = tokens.textLow)
                        }
                    }
                }
            }
        }
    }

    /** Settings → Look & feel → Drawer & search: what used to sit under Drawer on the main page. */
    @Composable
    private fun DrawerPage(settings: LauncherSettings, onHiddenApps: () -> Unit, onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        val apps by masala.launcher.apps.collectAsStateWithLifecycle()
        fun update(change: (LauncherSettings) -> LauncherSettings) {
            scope.launch { masala.settings.update(change) }
        }
        SettingsScaffold(stringResource(R.string.drawer_page_title), onBack) {
            SettingsToggleRow(stringResource(R.string.settings_auto_launch), settings.autoLaunch) { on -> update { it.copy(autoLaunch = on) } }
            SettingsToggleRow(stringResource(R.string.settings_drawer_keyboard), settings.drawerKeyboard) { on -> update { it.copy(drawerKeyboard = on) } }
            SettingsNavRow(
                title = stringResource(R.string.settings_hidden_apps),
                summary = stringResource(
                    R.string.settings_hidden_apps_summary,
                    apps.count { it.visibility == Visibility.HIDDEN },
                    apps.count { it.visibility == Visibility.SEARCH_ONLY },
                ),
                onClick = onHiddenApps,
            )
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

    /** Home's input card, already holding [prefill], for the user to finish in their own words. */
    private fun askInChat(prefill: String) {
        masala.commands.openInput(prefill)
        startActivity(Intent(this, com.samosaai.masala.HomeActivity::class.java).putExtra(com.samosaai.masala.HomeActivity.EXTRA_SHOW_INPUT, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    /** Every stored mini-app, so a created app is never invisible (plan: Lane D follow-ups). */
    @Composable
    private fun MiniAppsPage(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        val apps by remember { masala.miniApps.all }.collectAsStateWithLifecycle(emptyList())
        val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
        SettingsScaffold(stringResource(R.string.settings_miniapps), onBack = onBack) {
            Button(onClick = { askInChat(getString(R.string.miniapp_create_prefill)) }) { Text(stringResource(R.string.miniapp_create)) }
            var filter by rememberSaveable { mutableStateOf("") }
            // The search sits below Create (Batch A added the button on top), and only for long lists.
            // Also while a filter is set, so it can't be left hiding rows with no field to clear it.
            if (apps.size > 5 || filter.isNotEmpty()) ListSearchField(filter, { filter = it }, stringResource(R.string.miniapps_filter))
            val shown = remember(apps, filter) {
                val q = filter.trim().lowercase()
                if (q.isEmpty()) apps else apps.filter { it.title.lowercase().contains(q) }
            }
            if (apps.isEmpty()) {
                Text(stringResource(R.string.miniapps_empty), style = MaterialTheme.typography.bodyMedium)
            } else if (shown.isEmpty()) {
                Text(stringResource(R.string.miniapps_empty_no_match), style = MaterialTheme.typography.bodyMedium)
            }
            shown.forEach { row ->
                var confirmDelete by remember(row.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MiniAppIconView(row.icon, row.title, 40.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                format.format(java.util.Date(row.updatedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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

    /** The widget library in one place: preview, add to home, duplicate, delete (plan W2). */
    @Composable
    private fun WidgetsPage(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        val entries by remember { masala.widgetLibrary.watch() }.collectAsStateWithLifecycle(emptyList())
        val snapshot by masala.contextService.snapshot.collectAsStateWithLifecycle()
        val base by masala.layouts.base.collectAsStateWithLifecycle()
        fun toast(text: String) = android.widget.Toast.makeText(this@SettingsActivity, text, android.widget.Toast.LENGTH_SHORT).show()
        val source = remember(snapshot) {
            WidgetSource(snapshot = { snapshot }, store = masala.widgetStates, onAction = { toast(getString(R.string.widgets_preview_note)) }, streams = masala.streamSources, http = masala.httpValues)
        }
        // Template blurbs by widget id, so the Templates tab can say what each one does.
        val templatesByWidgetId = remember {
            runCatching { com.samosaai.masala.core.widgets.WidgetTemplates.all().associateBy { it.widget.id } }.getOrDefault(emptyMap())
        }
        SettingsScaffold(stringResource(R.string.settings_widgets), onBack = onBack) {
            Button(onClick = { askInChat(getString(R.string.widgets_describe_prefill)) }) { Text(stringResource(R.string.widgets_describe)) }
            var versionsId by remember { mutableStateOf<String?>(null) }
            versionsId?.let { id ->
                val title = entries.firstOrNull { it.spec.id == id }?.spec?.title ?: id
                WidgetVersionsDialog(id, title, onClose = { versionsId = null })
            }
            var tab by rememberSaveable { mutableStateOf(0) }
            val tabs = listOf(stringResource(R.string.widgets_mine), stringResource(R.string.widgets_templates), stringResource(R.string.widgets_cameras), stringResource(R.string.widgets_html))
            androidx.compose.material3.ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, title ->
                    androidx.compose.material3.Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, maxLines = 1, softWrap = false) })
                }
            }
            if (tab == 2) {
                CamerasTab()
                return@SettingsScaffold
            }
            if (tab == 3) {
                HtmlTab()
                return@SettingsScaffold
            }
            val mine = tab == 0
            var filter by rememberSaveable { mutableStateOf("") }
            val rows = remember(entries, mine, filter) {
                val q = filter.trim().lowercase()
                entries.filter { (it.createdBy != "template") == mine }
                    .filter { q.isEmpty() || it.spec.id.lowercase().contains(q) || (it.spec.title?.lowercase()?.contains(q) == true) }
            }
            if (entries.any { (it.createdBy != "template") == mine } && (rows.size > 5 || filter.isNotEmpty())) {
                ListSearchField(filter, { filter = it }, stringResource(R.string.widgets_filter))
            }
            if (rows.isEmpty()) {
                Text(
                    stringResource(if (filter.isNotBlank()) R.string.widgets_empty_no_match else R.string.widgets_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@SettingsScaffold
            }
            rows.forEach { (spec, _) ->
                var confirmDelete by remember(spec.id) { mutableStateOf(false) }
                var approval by remember(spec.id) { mutableStateOf<List<com.samosaai.masala.widgets.HttpApproval.Fetch>?>(null) }
                approval?.let { fetches ->
                    com.samosaai.masala.ui.home.widgets.HttpApprovalDialog(
                        fetches = fetches,
                        onApprove = {
                            approval = null
                            scope.launch {
                                masala.httpApproval.approve(spec.id, fetches.map { it.host })
                                val placed = masala.layouts.placeWidget("Add to home: ${spec.title ?: spec.id}", spec.id)
                                toast(
                                    if (placed.problems.isEmpty()) getString(R.string.widgets_added_on, masala.layouts.pageLabel(placed.pageId ?: spec.id))
                                    else getString(R.string.widgets_add_failed),
                                )
                            }
                        },
                        onDismiss = { approval = null },
                    )
                }
                val placements = remember(base, spec.id) { base?.let { LayoutEdits.widgetPlacements(it, spec.id) }.orEmpty() }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    WidgetCard(spec, rows = null, source = source)
                    if (!mine) {
                        templatesByWidgetId[spec.id]?.description?.let { blurb ->
                            Text(
                                blurb,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    Text(
                        if (placements.isEmpty()) stringResource(R.string.widgets_not_on_home)
                        else stringResource(R.string.widgets_on_home) + " · ${placements.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        if (placements.isEmpty()) {
                            TextButton(onClick = {
                                scope.launch {
                                    val pending = com.samosaai.masala.widgets.HttpApproval.needingApproval(spec, masala.httpApproval.approved(spec.id))
                                    if (pending.isEmpty()) {
                                        val placed = masala.layouts.placeWidget("Add to home: ${spec.title ?: spec.id}", spec.id)
                                        toast(
                                            if (placed.problems.isEmpty()) getString(R.string.widgets_added_on, masala.layouts.pageLabel(placed.pageId ?: spec.id))
                                            else getString(R.string.widgets_add_failed),
                                        )
                                    } else {
                                        approval = pending
                                    }
                                }
                            }) { Text(stringResource(R.string.widgets_add_to_home)) }
                        }
                        if (mine) {
                            TextButton(onClick = {
                                askInChat(getString(R.string.widgets_edit_prefill, spec.title ?: spec.id))
                            }) { Text(stringResource(R.string.widgets_edit)) }
                            TextButton(onClick = { versionsId = spec.id }) { Text(stringResource(R.string.widgets_versions)) }
                            TextButton(onClick = {
                                scope.launch {
                                    val known = masala.widgetLibrary.current().keys
                                    var copyId = "${spec.id}-copy"
                                    var n = 2
                                    while (copyId in known) copyId = "${spec.id}-copy-$n".also { n++ }
                                    masala.widgetLibrary.save(
                                        spec.copy(id = copyId, title = (spec.title ?: spec.id) + " (copy)"),
                                        createdBy = "user",
                                    )
                                }
                            }) { Text(stringResource(R.string.widgets_duplicate)) }
                            TextButton(onClick = { confirmDelete = true }) {
                                Text(stringResource(R.string.settings_version_delete))
                            }
                        } else {
                            // Templates stay pristine: personalizing goes through the Widget Maker in chat,
                            // which saves a new widget and leaves the template untouched.
                            TextButton(onClick = {
                                val template = templatesByWidgetId[spec.id]
                                askInChat(getString(R.string.widgets_customize_prefill, template?.name ?: spec.title ?: spec.id))
                            }) { Text(stringResource(R.string.widgets_customize)) }
                        }
                    }
                }
                HorizontalDivider()
                if (mine && confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text(stringResource(R.string.widgets_delete_title)) },
                        text = { Text(stringResource(R.string.widgets_delete_message, spec.title ?: spec.id)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDelete = false
                                scope.launch {
                                    masala.widgetLibrary.delete(spec.id)
                                    masala.layouts.removeWidgetRefs("Remove from home: ${spec.title ?: spec.id}", spec.id)
                                    masala.httpValues.clearWidget(spec.id)
                                    masala.httpApproval.clearWidget(spec.id)
                                }
                            }) { Text(stringResource(R.string.settings_version_delete)) }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_back)) } },
                    )
                }
            }
        }
    }

    /** Stream sources (plan §4.2): the registry behind video and snapshot widgets. */
    @Composable
    private fun CamerasTab() {
        val scope = rememberCoroutineScope()
        val sources by remember { masala.streamSources.sources }.collectAsStateWithLifecycle()
        fun toast(text: String) = android.widget.Toast.makeText(this@SettingsActivity, text, android.widget.Toast.LENGTH_SHORT).show()
        var adding by rememberSaveable { mutableStateOf(false) }
        var testing by remember { mutableStateOf<String?>(null) }
        var testLines by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        if (sources.isEmpty() && !adding) {
            Text(stringResource(R.string.widgets_sources_empty), style = MaterialTheme.typography.bodyMedium)
        }
        sources.forEach { source ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${source.kind.name.lowercase()} · ${source.url}" + if (source.hasCredentials) " · ${stringResource(R.string.widgets_source_login)}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                        testLines[source.id]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var mobile by remember(source.id) { mutableStateOf(masala.streamSources.isMeteredAllowed(source.id)) }
                    FilterChip(mobile, {
                        mobile = !mobile
                        masala.streamSources.setMeteredAllowed(source.id, mobile)
                    }, { Text(stringResource(R.string.widgets_source_mobile)) })
                    TextButton(
                        enabled = testing == null,
                        onClick = {
                            testing = source.id
                            scope.launch {
                                val streamUrl = masala.streamSources.urlWithCredentials(source.id)
                                val result = if (streamUrl == null) {
                                    com.samosaai.masala.widgets.StreamTest.Result(false, getString(R.string.widgets_source_login_gone))
                                } else {
                                    com.samosaai.masala.widgets.StreamTest.test(
                                        this@SettingsActivity, source.kind, streamUrl,
                                        masala.streamSources.snapshotUrlWithCredentials(source.id),
                                    )
                                }
                                testLines = testLines + (source.id to (if (result.ok) result.detail else getString(R.string.widgets_source_test_failed, result.detail)))
                                testing = null
                            }
                        },
                    ) { Text(if (testing == source.id) stringResource(R.string.widgets_source_testing) else stringResource(R.string.widgets_source_test)) }
                    TextButton(onClick = {
                        scope.launch {
                            masala.streamSources.remove(source.id)
                            toast(getString(R.string.widgets_source_removed))
                        }
                    }) { Text(stringResource(R.string.settings_version_delete)) }
                }
            }
            HorizontalDivider()
        }
        if (adding) {
            SourceForm(
                onSave = { adding = false },
                onCancel = { adding = false },
            )
        } else {
            Button(onClick = { adding = true }) { Text(stringResource(R.string.widgets_source_add)) }
        }
    }

    @Composable
    private fun SourceForm(onSave: () -> Unit, onCancel: () -> Unit) {
        val scope = rememberCoroutineScope()
        fun toast(text: String) = android.widget.Toast.makeText(this@SettingsActivity, text, android.widget.Toast.LENGTH_SHORT).show()
        var name by rememberSaveable { mutableStateOf("") }
        var kind by rememberSaveable { mutableStateOf(com.samosaai.masala.widgets.StreamKind.RTSP) }
        var url by rememberSaveable { mutableStateOf("") }
        var snapshotUrl by rememberSaveable { mutableStateOf("") }
        var username by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var transport by rememberSaveable { mutableStateOf("tcp") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.widgets_source_name)) })
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                com.samosaai.masala.widgets.StreamKind.entries.forEach { entry ->
                    FilterChip(entry == kind, { kind = entry }, { Text(entry.name.lowercase()) })
                }
            }
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.widgets_source_url)) })
            OutlinedTextField(snapshotUrl, { snapshotUrl = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.widgets_source_snapshot_url)) })
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.widgets_source_username)) })
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.widgets_source_password)) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
            if (kind == com.samosaai.masala.widgets.StreamKind.RTSP) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("tcp", "udp").forEach { option ->
                        FilterChip(option == transport, { transport = option }, { Text(option) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val (id, message) = masala.streamSources.add(
                            name, kind, url, snapshotUrl.ifBlank { null },
                            username.ifBlank { null }, password.ifEmpty { null },
                            transport.takeIf { kind == com.samosaai.masala.widgets.StreamKind.RTSP },
                        )
                        if (id == null) toast(message ?: getString(R.string.widgets_add_failed))
                        else onSave()
                    }
                }) { Text(stringResource(R.string.widgets_save)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_back)) }
            }
        }
    }

    /**
     * Edits one library widget: title plus the raw spec JSON, checker problems inline, and the last
     * versions to restore. The id never changes, so storage, taps and home refs carry over.
     */
    @Composable
    private fun HtmlTab() {
        val scope = rememberCoroutineScope()
        val apps by remember { masala.miniApps.all }.collectAsStateWithLifecycle(emptyList())
        val base by masala.layouts.base.collectAsStateWithLifecycle()
        fun toast(text: String) = android.widget.Toast.makeText(this@SettingsActivity, text, android.widget.Toast.LENGTH_SHORT).show()
        if (apps.isEmpty()) {
            Text(stringResource(R.string.widgets_html_empty), style = MaterialTheme.typography.bodyMedium)
            return
        }
        apps.forEach { row ->
            val placements = remember(base, row.id) { base?.let { com.samosaai.masala.core.layout.LayoutEdits.htmlPlacements(it, row.id) }.orEmpty() }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.samosaai.masala.miniapps.MiniAppIconView(row.icon, row.title, 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (placements.isEmpty()) stringResource(R.string.widgets_not_on_home)
                            else stringResource(R.string.widgets_on_home) + " · ${placements.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (placements.isEmpty()) {
                        TextButton(onClick = {
                            scope.launch {
                                val placed = masala.layouts.placeHtml("Add to home: ${row.title}", row.id)
                                toast(
                                    if (placed.problems.isEmpty()) getString(R.string.widgets_added_on, masala.layouts.pageLabel(placed.pageId ?: row.id))
                                    else getString(R.string.widgets_add_failed),
                                )
                            }
                        }) { Text(stringResource(R.string.widgets_add_to_home)) }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            masala.layouts.removeHtmlApp("Remove from home: ${row.title}", row.id)
                        }
                    }) {
                        Text(stringResource(R.string.widgets_remove_from_home))
                    }
                }
            }
            HorizontalDivider()
        }
    }

    /**
     * Earlier saves of one library widget, without JSON: restoring puts one back as the newest
     * version. The id never changes, so storage, taps and home refs carry over.
     */
    @Composable
    private fun WidgetVersionsDialog(specId: String, specTitle: String, onClose: () -> Unit) {
        val scope = rememberCoroutineScope()
        fun toast(text: String) = android.widget.Toast.makeText(this@SettingsActivity, text, android.widget.Toast.LENGTH_SHORT).show()
        var versions by remember(specId) { mutableStateOf<List<com.samosaai.masala.core.contract.WidgetSpec>?>(null) }
        androidx.compose.runtime.LaunchedEffect(specId) { versions = masala.widgetLibrary.history(specId) }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.widgets_versions) + " · $specTitle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val older = versions
                    if (older == null) {
                        Text("…", style = MaterialTheme.typography.bodyMedium)
                    } else if (older.size <= 1) {
                        Text(
                            stringResource(R.string.widgets_versions_empty),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            stringResource(R.string.widgets_versions) + " (${older.size})",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        older.drop(1).forEachIndexed { i, old ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.widgets_versions) + " ${older.size - i - 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    scope.launch {
                                        masala.widgetLibrary.save(old)
                                        toast(getString(R.string.widgets_added))
                                        onClose()
                                    }
                                }) { Text(stringResource(R.string.widgets_restore)) }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_back)) } },
        )
    }

    /** Every installed skill in one place, so a chat is one tap away (plan F4). */
    @Composable
    private fun SkillsPage(onBack: () -> Unit, onEditExpert: (String) -> Unit) {
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
            ListSearchField(filter, { filter = it }, stringResource(R.string.skills_filter))
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
                        // An expert has no chat of its own; its row leads to what can be changed.
                        if (skill.persona != null) {
                            TextButton(onClick = { onEditExpert(skill.id) }) { Text(stringResource(R.string.skills_edit)) }
                        } else {
                            TextButton(onClick = {
                                startActivity(SkillSheetActivity.intent(this@SettingsActivity, skill.id, null))
                            }) { Text(stringResource(R.string.skills_open)) }
                        }
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
    /** Set or change the PIN, or none at all. */
    private enum class PinMode { SET, CHANGE }

    /**
     * Hidden apps: behind an optional PIN, with a way back. "Show on home" returns the app to the
     * drawer and puts it back on home; "Show in drawer" only undoes the hiding.
     */
    @Composable
    private fun HiddenAppsPage(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
        val apps by masala.launcher.apps.collectAsStateWithLifecycle()
        val hidden = apps.filter { it.visibility != Visibility.DEFAULT }
        val pin = settings.hiddenPin
        var unlocked by remember(pin) { mutableStateOf(pin == null) }
        var pinMode by remember { mutableStateOf<PinMode?>(null) }
        var forgot by remember { mutableStateOf(false) }

        SettingsScaffold(stringResource(R.string.settings_hidden_apps), onBack = onBack) {
            if (!unlocked) {
                var attempt by remember { mutableStateOf("") }
                var wrong by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.hidden_locked_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = attempt,
                        onValueChange = { attempt = it.filter(Char::isDigit).take(HiddenPin.MAX_LENGTH); wrong = false },
                        singleLine = true,
                        label = { Text(stringResource(R.string.hidden_pin_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (wrong) {
                        Text(
                            stringResource(R.string.hidden_pin_wrong),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = { if (HiddenPin.matches(pin, attempt)) unlocked = true else wrong = true },
                        enabled = attempt.length >= HiddenPin.MIN_LENGTH,
                    ) { Text(stringResource(R.string.hidden_unlock)) }
                    TextButton(onClick = { forgot = true }) { Text(stringResource(R.string.hidden_forgot)) }
                }
                if (forgot) {
                    AlertDialog(
                        onDismissRequest = { forgot = false },
                        title = { Text(stringResource(R.string.hidden_forgot_title)) },
                        text = { Text(stringResource(R.string.hidden_forgot_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                forgot = false
                                scope.launch { masala.settings.update { it.copy(hiddenPin = null) } }
                            }) { Text(stringResource(R.string.hidden_forgot_confirm)) }
                        },
                        dismissButton = { TextButton(onClick = { forgot = false }) { Text(stringResource(R.string.action_cancel)) } },
                    )
                }
                return@SettingsScaffold
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (pin == null) R.string.hidden_no_pin else R.string.hidden_locked_on),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { pinMode = if (pin == null) PinMode.SET else PinMode.CHANGE }) {
                    Text(stringResource(if (pin == null) R.string.hidden_set_pin else R.string.hidden_change_pin))
                }
                if (pin != null) {
                    TextButton(onClick = { scope.launch { masala.settings.update { it.copy(hiddenPin = null) } } }) {
                        Text(stringResource(R.string.hidden_remove_pin))
                    }
                }
            }
            Text(
                stringResource(R.string.hidden_page_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            if (hidden.isEmpty()) Text(stringResource(R.string.settings_hidden_apps_empty))
            hidden.forEach { entry ->
                HiddenAppRow(
                    entry = entry,
                    onShow = {
                        scope.launch {
                            masala.launcher.setVisibility(entry.key, Visibility.DEFAULT)
                            android.widget.Toast.makeText(
                                this@SettingsActivity,
                                getString(R.string.hidden_restored, entry.label),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onShowAndPin = {
                        scope.launch {
                            masala.launcher.setVisibility(entry.key, Visibility.DEFAULT)
                            masala.layouts.pin(entry)
                            android.widget.Toast.makeText(
                                this@SettingsActivity,
                                getString(R.string.hidden_restored_home, entry.label),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }

        pinMode?.let { mode ->
            PinDialog(
                mode = mode,
                onDone = { entered ->
                    pinMode = null
                    scope.launch { masala.settings.update { it.copy(hiddenPin = HiddenPin.store(entered)) } }
                },
                onDismiss = { pinMode = null },
            )
        }
    }

    @Composable
    private fun HiddenAppRow(entry: AppEntry, onShow: () -> Unit, onShowAndPin: () -> Unit) {
        LauncherRow(
            label = entry.label,
            icon = null,
            iconContent = { com.samosaai.masala.launcher.AppIconTile(masala.icons, entry.key, entry.label, 40.dp) },
            subtitle = visibilityLabel(entry.visibility),
            onClick = null,
            onLongClick = null,
            longClickLabel = null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onShow) { Text(stringResource(R.string.hidden_show)) }
            TextButton(onClick = onShowAndPin) { Text(stringResource(R.string.hidden_show_and_pin)) }
        }
    }

    /** A 4–8 digit PIN, typed twice when setting it. Save stays off until both match. */
    @Composable
    private fun PinDialog(mode: PinMode, onDone: (String) -> Unit, onDismiss: () -> Unit) {
        var first by remember { mutableStateOf("") }
        var second by remember { mutableStateOf("") }
        val digits = { s: String -> s.filter(Char::isDigit).take(HiddenPin.MAX_LENGTH) }
        val valid = HiddenPin.isValid(first) && first == second
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(if (mode == PinMode.SET) R.string.hidden_pin_set_title else R.string.hidden_pin_change_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = first,
                        onValueChange = { first = digits(it) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.hidden_pin_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = digits(it) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.hidden_pin_repeat)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onDone(first) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    companion object {
        /** A [Page] name to open directly. */
        const val EXTRA_PAGE = "page"
    }
}
