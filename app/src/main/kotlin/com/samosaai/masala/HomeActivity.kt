package com.samosaai.masala

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.launcher.HomeRole
import com.samosaai.masala.ui.drawer.AppMenu
import com.samosaai.masala.ui.drawer.AppMenuActions
import com.samosaai.masala.ui.drawer.Drawer
import com.samosaai.masala.ui.drawer.RenameDialog
import com.samosaai.masala.ui.home.HomeScreen
import com.samosaai.masala.ui.settings.SettingsActivity
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.launch

/**
 * The home screen. `singleTask` with the HOME category; drawn edge to edge over the wallpaper. Back
 * closes the drawer and otherwise does nothing; pressing Home while here closes the drawer.
 */
class HomeActivity : ComponentActivity() {
    private var drawerOpen by mutableStateOf(false)
    private var isDefault by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light icons on the wallpaper; HomeContent switches them while a light drawer is open.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings, systemBars = false) { HomeContent(settings) }
        }
    }

    override fun onResume() {
        super.onResume()
        isDefault = HomeRole.isDefault(this)
        // Shortcut access comes with being the default home app.
        masala.launcher.refreshShortcuts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed: on home or coming back from an app, start from the home screen itself.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) drawerOpen = false
    }

    @Composable
    private fun HomeContent(settings: LauncherSettings) {
        val app = masala
        val launcher = app.launcher
        val apps by launcher.apps.collectAsStateWithLifecycle()
        val pinnedApps by launcher.pinnedApps.collectAsStateWithLifecycle()
        val pinnedShortcuts by launcher.pinnedShortcuts.collectAsStateWithLifecycle()
        val profiles by launcher.profiles.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var menuFor by remember { mutableStateOf<AppEntry?>(null) }
        var renaming by remember { mutableStateOf<AppEntry?>(null) }

        val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Turned down: the dialog may not come back, so offer the system screen instead.
            if (!HomeRole.isDefault(this)) HomeRole.openSettings(this)
        }

        val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = drawerOpen && lightSurface
                isAppearanceLightNavigationBars = drawerOpen && lightSurface
            }
        }

        // Always enabled, so Back never leaves home.
        BackHandler { drawerOpen = false }

        fun launch(entry: AppEntry) {
            if (!app.actions.launch(entry.key)) toast(getString(R.string.drawer_launch_failed, entry.label))
            drawerOpen = false
        }

        fun launchShortcut(shortcut: ShortcutInfo) {
            if (!app.actions.startShortcut(shortcut)) toast(getString(R.string.drawer_launch_failed, shortcut.shortLabel))
        }

        // Hidden under the open drawer, so TalkBack and touches only reach the drawer.
        if (!drawerOpen) HomeScreen(
            pinnedApps = pinnedApps,
            pinnedShortcuts = pinnedShortcuts,
            icons = app.icons,
            isDefault = isDefault,
            onSetDefault = { HomeRole.requestIntent(this)?.let(roleRequest::launch) ?: HomeRole.openSettings(this) },
            onOpenDrawer = { drawerOpen = true },
            onOpenSettings = ::openSettings,
            onLaunch = ::launch,
            onMenu = { menuFor = it },
            onLaunchShortcut = ::launchShortcut,
            onUnpinShortcut = {
                app.actions.unpin(it)
                launcher.refreshShortcuts()
            },
        )

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInVertically { it / 4 } + fadeIn(),
            exit = slideOutVertically { it / 4 } + fadeOut(),
        ) {
            Drawer(
                apps = apps,
                profiles = profiles,
                icons = app.icons,
                autoLaunch = settings.autoLaunch,
                onLaunch = ::launch,
                onMenu = { menuFor = it },
                onAsk = ::askMasala,
                onWorkPaused = { paused ->
                    if (!app.catalog.setWorkPaused(paused)) toast(getString(R.string.drawer_work_toggle_failed))
                },
                onOpenSettings = ::openSettings,
            )
        }

        menuFor?.let { entry ->
            AppMenu(
                entry = entry,
                actions = app.actions,
                icons = app.icons,
                canUninstall = app.catalog.canUninstall(entry.key),
                onDismiss = { menuFor = null },
                menu = object : AppMenuActions {
                    override fun launch(entry: AppEntry) = done { launch(entry) }
                    override fun launchShortcut(shortcut: ShortcutInfo) = done { launchShortcut(shortcut) }
                    override fun pinShortcut(shortcut: ShortcutInfo) = done {
                        app.actions.pin(shortcut)
                        launcher.refreshShortcuts()
                    }
                    override fun setPinned(entry: AppEntry, pinned: Boolean) = done {
                        scope.launch { launcher.setPinned(entry.key, pinned) }
                    }
                    override fun rename(entry: AppEntry) = done { renaming = entry }
                    override fun setVisibility(entry: AppEntry, visibility: Visibility) = done {
                        scope.launch { launcher.setVisibility(entry.key, visibility) }
                    }
                    override fun appInfo(entry: AppEntry) = done { app.actions.appInfo(entry.key) }
                    override fun uninstall(entry: AppEntry) = done { app.actions.uninstall(entry.key) }

                    private fun done(action: () -> Unit) {
                        menuFor = null
                        action()
                    }
                },
            )
        }

        renaming?.let { entry ->
            RenameDialog(
                entry = entry,
                onRename = { label ->
                    renaming = null
                    scope.launch { launcher.rename(entry.key, label) }
                },
                onDismiss = { renaming = null },
            )
        }
    }

    /** `MasalaAction.AskMasala`. A stub until the agent arrives in Phase 4. */
    private fun askMasala(prompt: String) {
        toast(getString(R.string.drawer_ask_stub))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
