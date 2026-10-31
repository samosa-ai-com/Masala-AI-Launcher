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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.samosaai.masala.ui.home.DrawerDrag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.samosaai.masala.launcher.HomeSearchSources
import com.samosaai.masala.ui.drawer.AppMenu
import com.samosaai.masala.ui.drawer.AppMenuActions
import com.samosaai.masala.ui.drawer.Drawer
import com.samosaai.masala.ui.drawer.blockTouches
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
import com.samosaai.masala.ui.home.SearchPill
import com.samosaai.masala.ui.home.ListeningOverlay
import com.samosaai.masala.ui.home.WorkingSheet
import androidx.compose.material3.Text
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
import com.samosaai.masala.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import com.samosaai.masala.ui.onboarding.WelcomeActivity

/**
 * The home screen. `singleTask` with the HOME category; drawn edge to edge over the wallpaper. Back
 * closes the drawer and otherwise does nothing; pressing Home while here closes the drawer.
 */
class HomeActivity : ComponentActivity() {
    private var drawerOpen by mutableStateOf(false)
    private var isDefault by mutableStateOf(true)
    private var homePresses by mutableStateOf(0)
    /** Bumped by the search pill: the drawer opens with its search field focused. */
    private var searchRequests by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light icons on the wallpaper; HomeContent switches them while a light drawer is open.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Once per creation: a rotation while Welcome is in flight must not launch it again.
        if (savedInstanceState == null) showWelcomeOnce()
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings, systemBars = false) { HomeContent(settings) }
        }
    }

    /** First run only; a phone that already has Masala as home skips it (and it's marked done). */
    private fun showWelcomeOnce() {
        lifecycleScope.launch {
            val app = masala
            if (app.settings.settings.first().welcomeDone) return@launch
            if (HomeRole.isDefault(this@HomeActivity)) {
                app.settings.update { it.copy(welcomeDone = true) }
            } else {
                startActivity(Intent(this@HomeActivity, WelcomeActivity::class.java))
            }
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

        // A newly applied layout arrives section by section, with an Undo offer (UI refresh U4).
        val history by app.layouts.historyState.collectAsStateWithLifecycle()
        val currentVersion = history.versions.firstOrNull { it.current }?.id
        var lastVersion by remember { mutableStateOf<Long?>(null) }
        var entrySignal by remember { mutableStateOf(0) }
        var undoOffer by remember { mutableStateOf(false) }
        var undoing by remember { mutableStateOf(false) }
        LaunchedEffect(currentVersion) {
            val before = lastVersion
            lastVersion = currentVersion
            if (before == null || currentVersion == null || before == currentVersion) return@LaunchedEffect
            entrySignal++
            if (undoing) {
                undoing = false
                return@LaunchedEffect
            }
            undoOffer = true
            kotlinx.coroutines.delay(6_000)
            undoOffer = false
        }

        // Always enabled, so Back never leaves home.
        BackHandler { drawerOpen = false }

        // One progress (0 = home, 1 = drawer) drives both layers. Both stay composed, so opening and
        // closing the drawer never rebuilds the home pager or the drawer list (plan UI refresh U0).
        // The drawer's state is read only inside the [Isolated] layers and the effects below, never in this
        // function's own body: a change would otherwise recompose all of home in the middle of the slide.
        val drawerProgress = remember { Animatable(0f) }
        val drawerShown = remember { derivedStateOf { drawerProgress.value > 0f } }
        val drawerSettled = remember { derivedStateOf { drawerOpen && drawerProgress.value >= 1f } }
        val drawerSpring = remember { spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow) }
        // The release's fling velocity, consumed by the animation below. The release only records
        // it and flips the state; the effect below is the single place that animates, so a
        // cancelled animation can never strand the state behind the progress.
        var releaseVelocity by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            snapshotFlow { drawerOpen }.collectLatest { open ->
                val target = if (open) 1f else 0f
                // A released drag already animates there with the finger's speed; don't restart it.
                val v = releaseVelocity
                releaseVelocity = 0f
                if (!(drawerProgress.isRunning && drawerProgress.targetValue == target)) drawerProgress.animateTo(target, drawerSpring, initialVelocity = v)
            }
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
                    if (drawerOpen == open) {
                        // No state change, so the effect below won't run: settle directly. Without
                        // this a partial drag that releases below the threshold freezes mid-slide
                        // with no later event able to move it.
                        scope.launch {
                            drawerProgress.animateTo(if (open) 1f else 0f, drawerSpring, initialVelocity = -velocityY / distance)
                        }
                    } else {
                        // Record the fling for the effect to consume; it owns the animation, so the
                        // two can never fight over the same frame. (Only here, so a sub-threshold
                        // release can't leak a stale velocity into a later tap-to-open.)
                        releaseVelocity = -velocityY / distance
                        drawerOpen = open
                    }
                }
            }
        }

        fun launch(entry: AppEntry, bounds: android.graphics.Rect? = null) {
            if (!app.actions.launch(entry.key, bounds, window.decorView)) toast(getString(R.string.drawer_launch_failed, entry.label))
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
                override suspend fun speak(text: String) = app.speakReply(text)
                override fun stopSpeaking() = app.stopSpeaking()
                override fun speaking() = app.speaking
                override suspend fun runNow(skillId: String) = app.runSkillTaskNow(skillId)
                override suspend fun claimAutoRead(run: com.samosaai.masala.data.SkillRunEntity) = app.claimAutoRead(run)
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
        LaunchedEffect(lightSurface) {
            snapshotFlow { drawerProgress.value > 0.5f }.collect { mostlyOpen ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = mostlyOpen && lightSurface
                    isAppearanceLightNavigationBars = mostlyOpen && lightSurface
                }
            }
        }

        val activeModes by app.modes.active.collectAsStateWithLifecycle()
        val patches by app.modes.patches.collectAsStateWithLifecycle()
        val modeName = patches.filter { it.id in activeModes }.joinToString(" · ") { it.name }.ifBlank { null }
        val render = remember(apps, settings.homeLocked, modeName) {
            RenderContext(
                icons = app.icons,
                apps = apps,
                clock = { snapshot.now },
                locked = settings.homeLocked,
                onAction = ::run,
                onAppMenu = { menuFor = it },
                onClockClick = { run(MasalaAction.RunTool("show_alarms")) },
                skillWidgets = skillWidgets,
                modeName = modeName,
                onModeChip = ::openSettings,
                onLaunchFrom = { action, bounds ->
                    val a = action as? MasalaAction.LaunchApp
                    val entry = a?.let { render0 -> apps.firstOrNull { it.key.packageName == render0.packageName && it.key.user == render0.user && (render0.activity == null || it.key.activity == render0.activity) } }
                    if (entry != null) launch(entry, bounds) else run(action)
                },
            )
        }
        // Faded out under the open drawer and hidden from TalkBack; the drawer above takes the touches.
        Isolated { Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = drawerProgress.value
                    alpha = 1f - p
                    compositingStrategy = drawerLayerStrategy(p)
                    scaleX = 1f - (1f - Motion.HOME_UNDER_DRAWER_SCALE) * p
                    scaleY = 1f - (1f - Motion.HOME_UNDER_DRAWER_SCALE) * p
                }
                .then(if (drawerOpen) Modifier.clearAndSetSemantics { } else Modifier)
                // Set on release, not while the opening swipe is still moving home's own gesture.
                .blockTouches(drawerOpen),
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
            entrySignal = entrySignal,
            drawerDrag = drawerDrag.takeIf { settings.swipeUp == MasalaAction.OpenDrawer },
            aboveDock = {
                val command by app.commands.state.collectAsStateWithLifecycle()
                // The pill makes way for the reply card while a command is shown.
                if (command == CommandState.Idle) {
                    SearchPill(onSearch = { drawerOpen = true; searchRequests++ }, mic = rememberMicPress(app), mode = app.aiSettings.profile.value.micMode)
                }
            },
        ) } }

        // Listening, work and replies (UI refresh U4); a command runs on if home is left. Hidden while the
        // drawer is shown; the reply card stays composed under it, because taking it out and putting it
        // back cost a slow frame at the start of every open and close.
        Isolated {
            val hidden = drawerOpen || drawerShown.value
            val command by app.commands.state.collectAsStateWithLifecycle()
            val level by app.commands.micLevel.collectAsStateWithLifecycle()
            val words by app.commands.liveWords.collectAsStateWithLifecycle()
            when (val c = command) {
                CommandState.Listening -> if (!hidden) ListeningOverlay(
                    character = settings.character,
                    level = { level },
                    words = words,
                    source = app.aiSettings.profile.value.voiceIn?.preset?.name ?: getString(R.string.listening_on_device),
                    onCancel = { app.commands.dismiss() },
                    onStop = { if (app.aiSettings.profile.value.micMode == com.samosaai.masala.core.ai.MicMode.HOLD) app.commands.stopListening() else app.commands.finishListening() },
                    onType = { app.commands.dismiss(); app.commands.openInput() },
                    hold = app.aiSettings.profile.value.micMode == com.samosaai.masala.core.ai.MicMode.HOLD,
                )
                is CommandState.Thinking -> if (!hidden) Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                    WorkingSheet(c, settings.character, onStop = { app.commands.dismiss() })
                }
                else -> Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (drawerProgress.value > 0f) 0f else 1f }
                        .then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier)
                        .blockTouches(hidden)
                        .statusBarsPadding().navigationBarsPadding().imePadding(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    ReplyCard(app, settings.readRepliesAloud, sheets = true, character = settings.character)
                }
            }
            if (undoOffer && !hidden) {
                Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 150.dp), contentAlignment = Alignment.BottomCenter) {
                    com.samosaai.masala.ui.components.GlassPill {
                        Text(getString(R.string.layout_applied), color = com.samosaai.masala.ui.theme.MasalaColors.Text)
                        androidx.compose.material3.TextButton(onClick = {
                            undoOffer = false
                            undoing = true
                            scope.launch { app.layouts.undo() }
                        }) { Text(getString(R.string.layout_undo), color = com.samosaai.masala.ui.theme.MasalaColors.Gold) }
                    }
                }
            }
        }

        // Above the working sheet and the reply card: a confirmation must never be hidden by them.
        ConfirmationSheet(app.confirmationGate)

        Isolated { if (drawerReady || drawerOpen) Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = drawerProgress.value
                    alpha = p
                    compositingStrategy = drawerLayerStrategy(p)
                    // Closed: moved off screen, so it neither draws nor takes touches.
                    translationY = if (p <= 0f) size.height else (1f - p) * size.height / 4f
                }
                .then(if (!drawerShown.value) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            val searchSources = remember { HomeSearchSources(this@HomeActivity, app) }
            // File hits ask the model, whose reply card only renders with the drawer closed;
            // external targets would otherwise land back on an open drawer. Dismiss first.
            val dismissingSources = remember(searchSources) {
                object : com.samosaai.masala.ui.drawer.SearchSources by searchSources {
                    override fun openShortcut(shortcut: ShortcutInfo) { drawerOpen = false; searchSources.openShortcut(shortcut) }
                    override fun openSkill(skill: com.samosaai.masala.core.contract.MasalaSkill) { drawerOpen = false; searchSources.openSkill(skill) }
                    override fun openSetting(entry: com.samosaai.masala.ui.drawer.SettingsEntry) { drawerOpen = false; searchSources.openSetting(entry) }
                    override fun openFile(hit: com.samosaai.masala.ui.drawer.FileHit) { drawerOpen = false; searchSources.openFile(hit) }
                    override fun webSearch(query: String) { drawerOpen = false; searchSources.webSearch(query) }
                }
            }
            // Suggestions and usage access are read again each time the drawer opens.
            var suggested by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
            var usageAccess by remember { mutableStateOf(false) }
            // After the open animation has settled, and only when the suggestions actually change, so a
            // database read never recomposes the grid mid-slide.
            val settled = drawerSettled.value
            LaunchedEffect(settled) {
                if (!settled) return@LaunchedEffect
                searchSources.reset()
                usageAccess = app.usage.hasUsageAccess()
                val zone = java.time.ZoneId.systemDefault()
                val now = System.currentTimeMillis()
                val launches = app.usage.launches(com.samosaai.masala.core.apps.UsageRange.MONTH.since(now, zone), apps)
                val byId = apps.associateBy { it.key.id }
                val eligible = byId.filterValues { it.visibility == Visibility.DEFAULT }.keys
                val t = java.time.LocalTime.now(zone)
                val next = com.samosaai.masala.core.apps.UsageRank.suggestedNow(launches.filter { it.key in eligible }, t.hour * 60 + t.minute, zone, { byId[it]?.label ?: it })
                    .mapNotNull { byId[it] }.filter { it.visibility == Visibility.DEFAULT }
                if (next.map { it.key } != suggested.map { it.key }) suggested = next
            }
            val globeSource = remember(apps, usageAccess, settings.usageAsked) { com.samosaai.masala.ui.drawer.GlobeSource(
                ranked = { range ->
                    val zone = java.time.ZoneId.systemDefault()
                    val launches = app.usage.launches(range.since(System.currentTimeMillis(), zone), apps)
                    val visible = apps.filter { it.visibility == Visibility.DEFAULT }
                    val byId = visible.associateBy { it.key.id }
                    com.samosaai.masala.core.apps.UsageRank.top(
                        com.samosaai.masala.core.apps.UsageRank.counts(launches, Long.MIN_VALUE), byId.keys, { byId[it]?.label ?: it },
                    ).mapNotNull { r -> byId[r.key]?.let { it to r.count } }
                },
                usageAccess = usageAccess,
                asked = settings.usageAsked,
                onAllow = {
                    scope.launch { app.settings.update { it.copy(usageAsked = true) } }
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                },
                onSkip = { scope.launch { app.settings.update { it.copy(usageAsked = true) } } },
            ) }
            Drawer(
                apps = apps,
                profiles = profiles,
                icons = app.icons,
                autoLaunch = settings.autoLaunch,
                onLaunch = ::launch,
                layout = settings.drawerLayout,
                onLayout = { l -> scope.launch { app.settings.update { it.copy(drawerLayout = l) } } },
                suggested = suggested,
                globe = globeSource,
                sources = dismissingSources,
                onMenu = { menuFor = it },
                onAsk = ::askMasala,
                onWorkPaused = { paused ->
                    if (!app.catalog.setWorkPaused(paused)) toast(getString(R.string.drawer_work_toggle_failed))
                },
                onOpenSettings = ::openSettings,
                open = drawerOpen,
                focusSignal = searchRequests,
                autoKeyboard = settings.drawerKeyboard,
                settled = drawerSettled.value,
            )
        } }

        menuFor?.let { entry ->
            AppMenu(
                entry = entry.copy(pinned = app.layouts.isPinned(entry)),
                actions = app.actions,
                icons = app.icons,
                canUninstall = app.catalog.canUninstall(entry.key),
                onDismiss = { menuFor = null },
                menu = object : AppMenuActions {
                    override fun launch(entry: AppEntry) = done { launch(entry, null) }
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

/** Gives [content] a recomposition scope of its own: state read inside recomposes only [content]. */
@Composable
private fun Isolated(content: @Composable () -> Unit) = content()

/**
 * How home and the drawer are drawn at drawer progress [p]. While the drawer moves, each is drawn once
 * into its own layer and only the layer is faded, scaled and slid: redrawing every text at a new scale
 * each frame made the glyph cache rasterise the clock again on every frame. At rest, alpha goes onto
 * each draw instead, so no full-screen buffer is kept.
 */
private fun drawerLayerStrategy(p: Float): CompositingStrategy =
    if (p > 0f && p < 1f) CompositingStrategy.Offscreen else CompositingStrategy.ModulateAlpha
