package com.samosaai.masala

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.samosaai.masala.ui.home.DrawerDrag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.samosaai.masala.actions.ActionDispatcher
import com.samosaai.masala.core.contract.MasalaAction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.samosaai.masala.agent.CommandState
import com.samosaai.masala.ui.home.AskButton
import com.samosaai.masala.ui.home.MicButton
import com.samosaai.masala.ui.home.ReplyCard
import com.samosaai.masala.ui.home.rememberMicPress
import com.samosaai.masala.ui.home.ConfirmationSheet
import com.samosaai.masala.ui.home.HomeScreen
import com.samosaai.masala.ui.home.RenderContext
import com.samosaai.masala.scheduled.ScheduledTaskScheduler
import com.samosaai.masala.skills.SkillScheduling
import com.samosaai.masala.ui.home.SkillWidgetSource
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
    private var homePresses by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light icons on the wallpaper; HomeContent switches them while a light drawer is open.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings, systemBars = false) { HomeContent(settings) }
        }
    }

    override fun onPause() {
        masala.contextService.setHomeVisible(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Rules are evaluated again on resume and then on the minute tick (plan Phase 2).
        masala.contextService.setHomeVisible(true)
        isDefault = HomeRole.isDefault(this)
        // Shortcut access comes with being the default home app.
        masala.launcher.refreshShortcuts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed: on home or coming back from an app, start from the home screen itself.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            drawerOpen = false
            homePresses++
        }
    }

    @Composable
    private fun HomeContent(settings: LauncherSettings) {
        val app = masala
        val launcher = app.launcher
        val apps by launcher.apps.collectAsStateWithLifecycle()
        val layout by app.layouts.resolved.collectAsStateWithLifecycle()
        val snapshot by app.contextService.snapshot.collectAsStateWithLifecycle()
        val pinnedShortcuts by launcher.pinnedShortcuts.collectAsStateWithLifecycle()
        val profiles by launcher.profiles.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var menuFor by remember { mutableStateOf<AppEntry?>(null) }
        var renaming by remember { mutableStateOf<AppEntry?>(null) }

        val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Turned down: the dialog may not come back, so offer the system screen instead.
            if (!HomeRole.isDefault(this)) HomeRole.openSettings(this)
        }

        // Always enabled, so Back never leaves home.
        BackHandler { drawerOpen = false }

        // One progress (0 = home, 1 = drawer) drives both layers. Both stay composed, so opening and
        // closing the drawer never rebuilds the home pager or the drawer list (plan UI refresh U0).
        val drawerProgress = remember { Animatable(0f) }
        val drawerShown by remember { derivedStateOf { drawerProgress.value > 0f } }
        val drawerMostlyOpen by remember { derivedStateOf { drawerProgress.value > 0.5f } }
        val drawerSettled by remember { derivedStateOf { drawerOpen && drawerProgress.value >= 1f } }
        val drawerSpring = remember { spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow) }
        LaunchedEffect(drawerOpen) {
            // The release animation below already drives toward the same target with the fling's
            // velocity; replaying it here with velocity 0 would cancel that momentum (the second
            // animateTo wins). External changes (Back, tap) have no animation in flight, so they
            // still animate from here.
            val target = if (drawerOpen) 1f else 0f
            if (drawerProgress.targetValue != target) drawerProgress.animateTo(target, drawerSpring)
        }
        // Composed once home has drawn its first frame, so the first open is as quick as the rest.
        var drawerReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos { }
            drawerReady = true
        }
        val windowHeight = LocalWindowInfo.current.containerSize.height.toFloat()
        val drawerDrag = remember(windowHeight) {
            object : DrawerDrag {
                // The drawer is fully open after this much travel.
                val distance = (windowHeight * 0.4f).coerceAtLeast(1f)

                override fun onDrag(upPx: Float) {
                    drawerReady = true
                    scope.launch { drawerProgress.snapTo((upPx / distance).coerceIn(0f, 1f)) }
                }

                override fun onRelease(velocityY: Float) {
                    val open = velocityY < -FLING_VELOCITY || (velocityY < FLING_VELOCITY && drawerProgress.value > 0.35f)
                    drawerOpen = open
                    scope.launch {
                        drawerProgress.animateTo(if (open) 1f else 0f, drawerSpring, initialVelocity = -velocityY / distance)
                    }
                }
            }
        }

        fun launch(entry: AppEntry) {
            if (!app.actions.launch(entry.key)) toast(getString(R.string.drawer_launch_failed, entry.label))
            drawerOpen = false
        }

        fun launchShortcut(shortcut: ShortcutInfo) {
            if (!app.actions.startShortcut(shortcut)) toast(getString(R.string.drawer_launch_failed, shortcut.shortLabel))
        }

        // Job progress notifications need this on Android 13 and newer; asked for when a schedule is accepted.
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val skillWidgets = remember {
            object : SkillWidgetSource {
                override fun skill(id: String) = app.skills.getSkillById(id)
                override fun latest(skillId: String) = app.database.skillRuns().latestFor(skillId)
                override fun nextRunAt(skillId: String) = app.skillScheduling.nextRunAt(skillId)
                override fun scheduleOffer(skillId: String) = app.skills.getSkillById(skillId)?.let { app.skillScheduling.offerFor(it, SkillScheduling.now()) }
                override fun acceptSchedule(offer: SkillScheduling.Offer) {
                    app.skillScheduling.accept(offer, System.currentTimeMillis(), java.time.ZoneId.systemDefault())
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { ScheduledTaskScheduler.reschedule(app) }
                }
                override fun declineSchedule(skillId: String) = app.skillScheduling.decline(skillId)
            }
        }
        val screen = remember {
            object : ActionDispatcher.Screen {
                override fun openDrawer(query: String?) { drawerOpen = true }
                override fun ask(prompt: String) = askMasala(prompt)
            }
        }
        // Tools wait for the permission answer themselves (permissions/PermissionGate).
        fun run(action: MasalaAction) {
            scope.launch {
                when (val outcome = app.dispatcher.dispatch(action, screen)) {
                    is ActionDispatcher.Outcome.Done -> outcome.message?.takeIf { it.isNotBlank() }?.let(::toast)
                    ActionDispatcher.Outcome.Declined -> toast(getString(R.string.action_declined))
                    is ActionDispatcher.Outcome.Failed -> toast(outcome.message)
                    is ActionDispatcher.Outcome.NeedsCapability -> toast(outcome.reason)
                }
            }
        }

        val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = drawerMostlyOpen && lightSurface
                isAppearanceLightNavigationBars = drawerMostlyOpen && lightSurface
            }
        }

        val render = remember(apps, settings.homeLocked) {
            RenderContext(
                icons = app.icons,
                apps = apps,
                clock = { snapshot.now },
                locked = settings.homeLocked,
                onAction = ::run,
                onAppMenu = { menuFor = it },
                onClockClick = { run(MasalaAction.RunTool("show_alarms")) },
                skillWidgets = skillWidgets,
            )
        }
        // Faded out under the open drawer and hidden from TalkBack; the drawer above takes the touches.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = drawerProgress.value
                    alpha = 1f - p
                    // Alpha on each draw instead of a full-screen offscreen buffer every frame.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    scaleX = 1f - 0.04f * p
                    scaleY = 1f - 0.04f * p
                }
                .then(if (drawerOpen) Modifier.clearAndSetSemantics { } else Modifier),
        ) { HomeScreen(
            layout = layout,
            render = render,
            pinnedShortcuts = pinnedShortcuts,
            isDefault = isDefault,
            onSetDefault = { HomeRole.requestIntent(this@HomeActivity)?.let(roleRequest::launch) ?: HomeRole.openSettings(this@HomeActivity) },
            onSwipeUp = { run(settings.swipeUp) },
            onSwipeDown = { run(settings.swipeDown) },
            onOpenSettings = ::openSettings,
            onLaunchShortcut = ::launchShortcut,
            onUnpinShortcut = {
                app.actions.unpin(it)
                launcher.refreshShortcuts()
            },
            homePresses = homePresses,
            drawerDrag = drawerDrag.takeIf { settings.swipeUp == MasalaAction.OpenDrawer },
        ) }

        ConfirmationSheet(app.confirmationGate)

        // The reply card and the mic; the card stays up until closed, and the command runs on if home is left.
        if (!drawerOpen && !drawerShown) {
            val command by app.commands.state.collectAsStateWithLifecycle()
            val mic = rememberMicPress(app)
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                ReplyCard(app, settings.readRepliesAloud)
            }
            if (command == CommandState.Idle) {
                Box(Modifier.fillMaxSize().navigationBarsPadding().padding(end = 16.dp, bottom = 120.dp), contentAlignment = Alignment.BottomEnd) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AskButton(onOpen = { app.commands.openInput() })
                        MicButton(mic)
                    }
                }
            }
        }

        if (drawerReady || drawerOpen) Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = drawerProgress.value
                    alpha = p
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    // Closed: moved off screen, so it neither draws nor takes touches.
                    translationY = if (p <= 0f) size.height else (1f - p) * size.height / 4f
                }
                .then(if (!drawerShown) Modifier.clearAndSetSemantics { } else Modifier),
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
                open = drawerOpen,
                autoKeyboard = settings.drawerKeyboard,
                settled = drawerSettled,
            )
        }

        menuFor?.let { entry ->
            AppMenu(
                entry = entry.copy(pinned = app.layouts.isPinned(entry)),
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
                        if (settings.homeLocked) {
                            toast(getString(R.string.home_locked_toast))
                        } else {
                            scope.launch {
                                val problems = if (pinned) app.layouts.pin(entry) else app.layouts.unpin(entry)
                                problems.firstOrNull()?.let { toast(it.toString()) }
                            }
                        }
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

    /** `MasalaAction.AskMasala`; an empty prompt (the Ask gesture) opens the reply card for typing. */
    private fun askMasala(prompt: String) {
        masala.commands.ask(prompt)
        drawerOpen = false
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private companion object {
        /** Release speed (px/s) that settles the drawer in the fling's direction whatever its position. */
        const val FLING_VELOCITY = 1200f
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
