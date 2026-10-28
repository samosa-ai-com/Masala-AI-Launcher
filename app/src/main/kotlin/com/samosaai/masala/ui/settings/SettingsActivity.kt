package com.samosaai.masala.ui.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.layout.LayoutTransfer
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.samosaai.masala.ui.components.LauncherRow
import com.samosaai.masala.ui.drawer.visibilityLabel
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.wiki.WikiActivity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The settings shell (plan Phase 1): home app, theme, text size, drawer, hidden apps. */
class SettingsActivity : ComponentActivity() {
    private enum class Page { MAIN, HIDDEN_APPS, LAYOUT }

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
                        Page.MAIN -> MainPage(settings, onHiddenApps = { page = Page.HIDDEN_APPS }, onLayout = { page = Page.LAYOUT })
                        Page.HIDDEN_APPS -> HiddenAppsPage(onBack = { page = Page.MAIN })
                        Page.LAYOUT -> LayoutPage(settings, onBack = { page = Page.MAIN })
                    }
                }
            }
        }
    }

    @Composable
    private fun MainPage(settings: LauncherSettings, onHiddenApps: () -> Unit, onLayout: () -> Unit) {
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

            SettingsSectionTitle(stringResource(R.string.settings_ask))
            SettingsToggleRow(stringResource(R.string.settings_read_replies), settings.readRepliesAloud) { on ->
                update { it.copy(readRepliesAloud = on) }
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

            SettingsNavRow(stringResource(R.string.wiki_title), stringResource(R.string.wiki_summary)) {
                startActivity(Intent(this@SettingsActivity, WikiActivity::class.java))
            }

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
