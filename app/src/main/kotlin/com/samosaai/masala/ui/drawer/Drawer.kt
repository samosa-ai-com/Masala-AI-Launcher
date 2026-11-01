package com.samosaai.masala.ui.drawer

import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samosaai.masala.R
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppSearch
import com.samosaai.masala.core.apps.ProfileKind
import com.samosaai.masala.core.apps.UsageRange
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.data.DrawerLayout
import com.samosaai.masala.launcher.AppCatalog
import com.samosaai.masala.launcher.AppIconTile
import com.samosaai.masala.launcher.IconCache
import com.samosaai.masala.launcher.rememberAppIcon
import com.samosaai.masala.ui.components.LauncherRow
import com.samosaai.masala.ui.theme.LocalMotion
import com.samosaai.masala.ui.theme.MasalaColors
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.theme.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Ranked search results and the text they were ranked for. */
private data class Ranked<T>(val query: String, val results: List<T>)

/** Pause after the last keystroke before a composing single match auto-launches. */
private const val AUTO_LAUNCH_PAUSE_MS = 800L

/** Radius of the semicircle the letters swing out on while sliding. */
private val ARC_RADIUS = 90.dp

/** How much the finger's letter grows at the deepest point of the arc. */
private const val LETTER_GROW = 1.1f

/** Extra room around the swung letters inside the disc behind them. */
private val ARC_DISC_PAD = 24.dp
private const val GLOBE_MAX = 30

/** Where the globe gets its counts and whether to ask for Usage access (decision 11). */
class GlobeSource(
    val ranked: suspend (UsageRange) -> List<Pair<AppEntry, Int>>,
    val usageAccess: Boolean,
    val asked: Boolean,
    val onAllow: () -> Unit,
    val onSkip: () -> Unit,
)

/** The A–Z section an app files under; anything not a letter goes under #. */
internal fun sectionOf(label: String): Char =
    AppSearch.normalize(label).firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'

/**
 * The app drawer (plan UI refresh U3, drawer boards): a dimmed glass sheet over the wallpaper with a
 * search field, then Grid (suggested now, A–Z sections, scrubber), Globe (sized by use) or List. With a
 * query, search covers apps, app shortcuts, skills, settings and indexed files, with Ask Masala first
 * when nothing matches well. Apps marked "search only" appear only in search; hidden apps never show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun Drawer(
    apps: List<AppEntry>,
    profiles: AppCatalog.Snapshot,
    icons: IconCache,
    autoLaunch: Boolean,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onAsk: (String) -> Unit,
    onWorkPaused: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    /** The drawer stays composed while closed; closing clears the search and drops the keyboard. */
    open: Boolean = true,
    /** Focus the field (and so open the keyboard) once the drawer has settled open, not only on tap. */
    autoKeyboard: Boolean = false,
    /** True once the open transition has finished, so the keyboard doesn't animate in during the slide. */
    settled: Boolean = true,
    /** Bumped by home's search pill: focus the field once the drawer settles. */
    focusSignal: Int = 0,
    layout: DrawerLayout = DrawerLayout.GRID,
    onLayout: (DrawerLayout) -> Unit = {},
    /** "Suggested now": apps opened around this time on earlier days. */
    suggested: List<AppEntry> = emptyList(),
    globe: GlobeSource? = null,
    sources: SearchSources = SearchSources.None,
) {
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val text = query.text
    val composing = query.composition != null
    val launch: (AppEntry) -> Unit = { onLaunch(it, null) }

    val browse = remember(apps) { apps.filter { it.visibility == Visibility.DEFAULT } }
    // Labels normalized once per catalog change; ranking runs off the main thread on each keystroke.
    val prepared = remember(apps) {
        AppSearch.prepare(apps.filter { it.visibility != Visibility.HIDDEN }) { listOf(it.label, it.originalLabel) }
    }
    val latestPrepared by rememberUpdatedState(prepared)
    var ranked by remember { mutableStateOf(Ranked("", emptyList<AppEntry>())) }
    LaunchedEffect(Unit) {
        snapshotFlow { query.text to latestPrepared }
            .mapLatest { (q, index) -> if (q.isBlank()) Ranked(q, emptyList()) else withContext(Dispatchers.Default) { Ranked(q, AppSearch.rank(q, index)) } }
            .collect { ranked = it }
    }
    val searching = text.isNotBlank()
    // Until the ranking for the current text arrives, keep showing the last one (never a stale auto-launch).
    val results = if (searching) ranked.results else browse
    val current = !searching || ranked.query == text
    LaunchedEffect(text, composing, ranked) {
        if (!autoLaunch || !current) return@LaunchedEffect
        AppSearch.autoLaunchTarget(text, composing, results)?.let(launch) ?: run {
            // Keyboards that keep composition open while typing (Samsung autocomplete) would
            // otherwise never auto-launch: a single match that survives a short pause launches
            // too, so the keyboard never has to close first.
            if (composing && AppSearch.autoLaunchTarget(text, false, results) != null) {
                delay(AUTO_LAUNCH_PAUSE_MS)
                AppSearch.autoLaunchTarget(text, false, results)?.let(launch)
            }
        }
    }

    // The other search sources, debounced a little so a fast typist doesn't query files per letter.
    var extra by remember { mutableStateOf(SearchGroups.build("", emptyList())) }
    LaunchedEffect(text, results) {
        if (!searching) { extra = SearchGroups.build("", emptyList()); return@LaunchedEffect }
        val skills = sources.skills(text)
        val settingsHits = sources.settings(text)
        extra = SearchGroups.build(text, results, skills = skills, settings = settingsHits, ask = { suggestionsFor(it) })
        delay(250)
        val shortcuts = sources.shortcuts(text)
        val files = sources.files(text)
        extra = SearchGroups.build(text, results, shortcuts, skills, settingsHits, files) { suggestionsFor(it) }
    }

    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val resultsState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var handledSignal by remember { mutableIntStateOf(focusSignal) }
    LaunchedEffect(open, settled, autoKeyboard, focusSignal) {
        if (open && settled && (autoKeyboard || focusSignal != handledSignal)) {
            handledSignal = focusSignal
            runCatching { focus.requestFocus() }
        }
        if (!open) {
            // Focus and keyboard go first, so the keyboard's composing text can't come back into the
            // emptied field on the next open.
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            query = TextFieldValue()
            // Only one of the two is on screen; requests don't wait for a layout that may never come.
            listState.requestScrollToItem(0)
            resultsState.requestScrollToItem(0)
            gridState.requestScrollToItem(0)
        }
    }
    // New results start at the top; applied in the next layout pass, without a frame of its own.
    LaunchedEffect(results) { resultsState.requestScrollToItem(0) }

    // Switches at once; the saved setting catches up (a settings write and read-back took a beat).
    var shownLayout by remember(layout) { mutableStateOf(layout) }
    val pickLayout: (DrawerLayout) -> Unit = { l -> shownLayout = l; onLayout(l) }

    // The globe's ranking lives here so typing in Globe layout can dim it above the search results.
    var range by rememberSaveable { mutableStateOf(UsageRange.WEEK) }
    var globeRanked by remember { mutableStateOf<List<GlobeApp>>(emptyList()) }
    var usageOrder by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(range, apps, open, shownLayout) {
        if (!open || globe == null || shownLayout != DrawerLayout.GLOBE) return@LaunchedEffect
        val r = globe.ranked(range)
        usageOrder = r.mapIndexed { i, (e, _) -> e.key.id to i }.toMap()
        globeRanked = withContext(Dispatchers.Default) { globeApps(r.take(GLOBE_MAX)) }
    }

    var showWork by rememberSaveable { mutableStateOf(false) }
    val work = profiles.work
    val tokens = MasalaTheme.tokens
    // A dimmed glass sheet: the wallpaper stays visible behind (home fades out under it).
    val sheet = if (tokens.dark) Color(0xD60C0A12) else MasalaColors.Paper.copy(alpha = 0.94f)
    Box(
        Modifier
            .fillMaxSize()
            // No pointer handler here: any listener on this root (even one that consumes nothing) made
            // the buttons and rows inside drop taps whose finger moved a little. Home keeps itself out
            // of reach instead ([blockTouches] in HomeActivity).
            .background(sheet),
    ) {
        // No Surface under the sheet any more, so rows get their text colour from here.
        CompositionLocalProvider(LocalContentColor provides tokens.textHigh) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(38.dp, 4.dp).clip(RoundedCornerShape(50)).background(tokens.textLow))
            SearchRow(
                query = query,
                onQuery = { query = it },
                focus = focus,
                onGo = {
                    // Enter while composing finishes the character instead (Olauncher §2.4).
                    if (!composing) {
                        if (extra.askFirst && text.isNotBlank()) onAsk(text.trim())
                        else {
                            // Async ranking may lag the text; rank once on submit so Enter
                            // never launches the previous query's hit.
                            val fresh = if (current) results else AppSearch.rank(text, latestPrepared)
                            fresh.firstOrNull()?.let(launch)
                                ?: text.takeIf { it.isNotBlank() }?.let { onAsk(it.trim()) }
                        }
                    }
                },
                onOpenSettings = onOpenSettings,
            )

            // Results and the browse view are both kept composed and the one not in use is hidden: building
            // the results list on the first letter, and the grid again after the last, each took a frame
            // of several times the budget.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().hiddenWhen(searching)) {
                    // Remembered: a new list on every letter made the hidden grid recompose with each one.
                    val shown = remember(browse, showWork, work == null) {
                        when {
                            work == null -> browse.filter { it.profile == ProfileKind.PERSONAL }
                            showWork -> browse.filter { it.profile == ProfileKind.WORK }
                            else -> browse.filter { it.profile == ProfileKind.PERSONAL }
                        }
                    }
                    AllAppsBar(
                        count = shown.size,
                        layout = shownLayout,
                        onLayout = pickLayout,
                        showWorkSwitch = work != null,
                        showWork = showWork,
                        onShowWork = { showWork = it },
                    )
                    if (showWork && work != null) WorkHeader(paused = work.paused, onWorkPaused = onWorkPaused)
                    when (shownLayout) {
                        DrawerLayout.GRID -> AppGrid(shown, suggested.takeIf { !showWork }.orEmpty(), icons, profiles, onLaunch, onMenu, gridState, Modifier.weight(1f))
                        DrawerLayout.LIST -> AppListWithRail(shown, icons, profiles, onLaunch, onMenu, listState, Modifier.weight(1f))
                        DrawerLayout.GLOBE -> GlobePage(shown, globeRanked, usageOrder, range, { range = it }, icons, open, globe, onLaunch, onMenu, profiles, Modifier.weight(1f))
                    }
                }
                // Ends above the keyboard, so only the rows that can be seen are composed on each letter.
                LazyColumn(state = resultsState, modifier = Modifier.fillMaxSize().hiddenWhen(!searching).imePadding()) {
                    if (searching) {
                        if (shownLayout == DrawerLayout.GLOBE && globeRanked.isNotEmpty()) {
                            item(key = "globe") {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Globe(globeRanked, icons, text, open, onLaunch = launch, onMenu = onMenu)
                                }
                            }
                        }
                        searchGroups(extra, text, sources, onAsk) {
                            if (results.isNotEmpty()) item(key = "h-apps") { GroupHeader(stringResource(R.string.search_apps)) }
                            items(results.take(12), key = { it.key.id }) { entry ->
                                AppRow(entry, icons, profiles, labelWork = true, onLaunch, onMenu, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/** Two suggested questions for the Ask card; plain on purpose (the model reads the rest). */
private fun suggestionsFor(q: String): List<String> = listOf("What do my files say about $q?", "Remind me about $q")

@Composable
private fun SearchRow(query: TextFieldValue, onQuery: (TextFieldValue) -> Unit, focus: FocusRequester, onGo: () -> Unit, onOpenSettings: () -> Unit) {
    val tokens = MasalaTheme.tokens
    val pill = RoundedCornerShape(50)
    val hint = stringResource(R.string.drawer_search_hint)
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.weight(1f).height(50.dp).clip(pill).background(Color.White.copy(alpha = if (tokens.dark) 0.08f else 0.6f))
                .border(1.dp, tokens.hairline, pill).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Search, null, tint = tokens.textMedium, modifier = Modifier.size(19.dp))
            Box(Modifier.weight(1f)) {
                if (query.text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = tokens.textMedium)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textHigh),
                    cursorBrush = SolidColor(tokens.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onGo() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = hint },
                )
            }
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.White.copy(alpha = if (tokens.dark) 0.08f else 0.6f)).border(1.dp, tokens.hairline, CircleShape),
        ) { Icon(Icons.Rounded.Settings, stringResource(R.string.drawer_settings), tint = tokens.textHigh) }
    }
}

@Composable
private fun AllAppsBar(count: Int, layout: DrawerLayout, onLayout: (DrawerLayout) -> Unit, showWorkSwitch: Boolean, showWork: Boolean, onShowWork: (Boolean) -> Unit) {
    val tokens = MasalaTheme.tokens
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.drawer_all_apps, count), style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = tokens.textMedium, modifier = Modifier.weight(1f))
        if (showWorkSwitch) {
            Segmented(listOf(stringResource(R.string.drawer_personal), stringResource(R.string.drawer_work_header)), if (showWork) 1 else 0) { onShowWork(it == 1) }
            Spacer(Modifier.width(8.dp))
        }
        LayoutButton(Icons.Rounded.Apps, stringResource(R.string.drawer_layout_grid), layout == DrawerLayout.GRID) { onLayout(DrawerLayout.GRID) }
        LayoutButton(Icons.Rounded.Public, stringResource(R.string.drawer_layout_globe), layout == DrawerLayout.GLOBE) { onLayout(DrawerLayout.GLOBE) }
        LayoutButton(Icons.AutoMirrored.Rounded.ViewList, stringResource(R.string.drawer_layout_list), layout == DrawerLayout.LIST) { onLayout(DrawerLayout.LIST) }
    }
}

@Composable
private fun LayoutButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val tokens = MasalaTheme.tokens
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp).padding(2.dp).clip(CircleShape).background(if (on) tokens.textHigh else Color.Transparent).semantics { selected = on },
    ) { Icon(icon, label, tint = if (on) MaterialTheme.colorScheme.surface else tokens.textMedium, modifier = Modifier.size(20.dp)) }
}

@Composable
internal fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val tokens = MasalaTheme.tokens
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier.heightIn(min = 30.dp).clip(RoundedCornerShape(50)).background(if (on) tokens.textHigh else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }.semantics { this.selected = on }.padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text(l, fontSize = 12.5.sp, color = if (on) MaterialTheme.colorScheme.surface else tokens.textMedium, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/**
 * The grid (drawer board): "Suggested now", then apps in A–Z sections, four across, with a scrubber on
 * the right that ticks under the finger and shows the letter in a bubble.
 */
@Composable
private fun AppGrid(
    apps: List<AppEntry>,
    suggested: List<AppEntry>,
    icons: IconCache,
    profiles: AppCatalog.Snapshot,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onMenu: (AppEntry) -> Unit,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    modifier: Modifier,
) {
    val sections = remember(apps) { apps.groupBy { sectionOf(it.label) }.toSortedMap(compareBy { if (it == '#') '[' else it }) }
    // Grid index of each section header, for the scrubber.
    val headerIndex = remember(sections, suggested) {
        var i = if (suggested.isNotEmpty()) 2 else 0
        sections.mapValues { (_, list) -> i.also { i += 1 + list.size } }
    }
    val scope = rememberCoroutineScope()
    var scrubbing by remember { mutableStateOf<Char?>(null) }
    val reduced = LocalMotion.current.reduced
    Box(modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = state,
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 30.dp),
        ) {
            if (suggested.isNotEmpty()) {
                item(key = "sug-h", span = { GridItemSpan(maxLineSpan) }) { GroupHeader(stringResource(R.string.drawer_suggested_now)) }
                item(key = "sug", span = { GridItemSpan(maxLineSpan) }) { SuggestedRow(suggested, icons, onLaunch, onMenu) }
            }
            sections.forEach { (letter, list) ->
                item(key = "h-$letter", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        letter.toString(), style = MaterialTheme.typography.labelMedium, color = MasalaTheme.tokens.textLow,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp).semantics { heading() },
                    )
                }
                items(list.size, key = { list[it].key.id }) { i -> AppCell(list[i], icons, profiles, onLaunch, onMenu) }
            }
            item(key = "end", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
        }
        Scrubber(
            letters = sections.keys.toList(),
            modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(end = 6.dp),
            onLetter = { c ->
                scrubbing = c
                headerIndex[c]?.let { scope.launch { state.scrollToItem(it) } }
            },
            onEnd = { scrubbing = null },
        )
        // With motion on, the letter under the thumb grows on the arc itself, so the bubble stays out of
        // the way. Reduced motion has no arc, so it keeps the bubble.
        if (reduced) scrubbing?.let { c ->
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 38.dp).size(54.dp)
                    .clip(RoundedCornerShape(topStart = 27.dp, topEnd = 27.dp, bottomStart = 27.dp, bottomEnd = 6.dp))
                    .background(MasalaTheme.tokens.accent),
                contentAlignment = Alignment.Center,
            ) { Text(c.toString(), fontSize = 26.sp, color = MasalaTheme.tokens.onAccent, style = MaterialTheme.typography.headlineSmall) }
        }
    }
}

/** The A–Z list with the same right-edge scrubber as [AppGrid]. */
@Composable
private fun AppListWithRail(
    apps: List<AppEntry>,
    icons: IconCache,
    profiles: AppCatalog.Snapshot,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onMenu: (AppEntry) -> Unit,
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
) {
    val sections = remember(apps) { apps.groupBy { sectionOf(it.label) }.toSortedMap(compareBy { if (it == '#') '[' else it }) }
    val headerIndex = remember(sections) {
        var i = 0
        sections.mapValues { (_, list) -> i.also { i += 1 + list.size } }
    }
    val scope = rememberCoroutineScope()
    var scrubbing by remember { mutableStateOf<Char?>(null) }
    val reduced = LocalMotion.current.reduced
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(end = 30.dp)) {
            sections.forEach { (letter, list) ->
                item(key = "h-$letter") {
                    Text(
                        letter.toString(), style = MaterialTheme.typography.labelMedium, color = MasalaTheme.tokens.textLow,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp).semantics { heading() },
                    )
                }
                items(list, key = { it.key.id }) { entry -> AppRow(entry, icons, profiles, labelWork = false, onLaunch, onMenu) }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
        Scrubber(
            letters = sections.keys.toList(),
            modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(end = 6.dp),
            onLetter = { c ->
                scrubbing = c
                headerIndex[c]?.let { scope.launch { state.scrollToItem(it) } }
            },
            onEnd = { scrubbing = null },
        )
        if (reduced) scrubbing?.let { c ->
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 38.dp).size(54.dp)
                    .clip(RoundedCornerShape(topStart = 27.dp, topEnd = 27.dp, bottomStart = 27.dp, bottomEnd = 6.dp))
                    .background(MasalaTheme.tokens.accent),
                contentAlignment = Alignment.Center,
            ) { Text(c.toString(), fontSize = 26.sp, color = MasalaTheme.tokens.onAccent, style = MaterialTheme.typography.headlineSmall) }
        }
    }
}

/** A–Z down the right edge; drag or tap to jump, with a haptic tick on each new letter. */
@Composable
internal fun Scrubber(letters: List<Char>, modifier: Modifier = Modifier, onLetter: (Char) -> Unit, onEnd: () -> Unit) {
    if (letters.isEmpty()) return
    val haptics = rememberHaptics()
    val tokens = MasalaTheme.tokens
    var heightPx by remember { mutableIntStateOf(1) }
    var last by remember { mutableStateOf<Char?>(null) }
    val latestLetters by rememberUpdatedState(letters)
    // 0 while the rail is idle, 1 while a finger is on it. Read only inside graphicsLayer below, so
    // the letters animate in the draw phase and a drag never recomposes the rail.
    val active = remember { Animatable(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val reduced = LocalMotion.current.reduced
    // The gesture block is keyed on Unit, so it must read the current setting, not the one it was
    // created with: toggling motion in Settings while the drawer is composed would keep the old value.
    val reducedNow by rememberUpdatedState(reduced)
    val settle = remember { spring<Float>(stiffness = Spring.StiffnessMediumLow) }
    fun pick(y: Float) {
        val l = latestLetters
        val c = l[(y / heightPx * l.size).toInt().coerceIn(0, l.lastIndex)]
        if (c != last) {
            last = c
            haptics.tick()
            onLetter(c)
        }
    }
    val label = stringResource(R.string.drawer_scrubber)
    val panel = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier.width(22.dp)) {
        // "A" starts about 30% down, within thumb reach; the letters spread to near the bottom.
        Spacer(Modifier.weight(0.3f))
        Column(
            Modifier.weight(0.7f).fillMaxWidth()
                .onGloballyPositioned { heightPx = it.size.height.coerceAtLeast(1) }
                // A halo behind the curve, following the finger: clear in the middle where the
                // letters sit, denser toward the rim, gone at the very edge. Drawn outside the
                // 22 dp strip, like the letters.
                .drawBehind {
                    val a = active.value
                    if (a <= 0f) return@drawBehind
                    val radius = (ARC_RADIUS + ARC_DISC_PAD).toPx()
                    // Concentric with the letters' arc and centred on the finger, so the swung
                    // letters always sit inside its clear middle.
                    val centre = Offset(size.width / 2, dragY)
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to panel.copy(alpha = 0.10f * a),
                            0.55f to panel.copy(alpha = 0.28f * a),
                            0.85f to panel.copy(alpha = 0.45f * a),
                            1f to panel.copy(alpha = 0f),
                            center = centre,
                            radius = radius,
                        ),
                        radius = radius,
                        center = centre,
                    )
                }
                .semantics { contentDescription = label }
                .pointerInput(Unit) {
                    // One gesture block: stacked tap+drag detectors cancel each other and
                    // drop the bubble at drag start.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragY = down.position.y
                        if (!reducedNow) scope.launch { active.animateTo(1f, settle) }
                        pick(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            dragY = change.position.y
                            pick(change.position.y)
                            change.consume()
                        }
                        last = null
                        onEnd()
                        if (!reducedNow) scope.launch { active.animateTo(0f, settle) }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            letters.forEachIndexed { i, c ->
                Text(
                    c.toString(), fontSize = 10.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                    color = if (c == last) tokens.accentText else tokens.textLow,
                    // The letters swing out on a semicircle centred on the finger: on the circle
                    // while within the radius, back on the rail outside it. The rail keeps its
                    // 22 dp touch strip and draws outside it (graphicsLayer does not clip).
                    modifier = Modifier.graphicsLayer {
                        val a = active.value
                        val radiusPx = ARC_RADIUS.toPx()
                        val pitch = heightPx.toFloat() / letters.size
                        val centre = (i + 0.5f) * pitch
                        val dy = (centre - dragY) / radiusPx
                        val t = sqrt((1f - dy * dy).coerceAtLeast(0f))
                        translationX = -t * radiusPx * a
                        scaleX = 1f + t * LETTER_GROW * a
                        scaleY = scaleX
                        alpha = 1f - (1f - t) * 0.65f * a
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SuggestedRow(apps: List<AppEntry>, icons: IconCache, onLaunch: (AppEntry, Rect?) -> Unit, onMenu: (AppEntry) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(shape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, MasalaTheme.tokens.hairline, shape).padding(vertical = 12.dp, horizontal = 4.dp),
    ) {
        apps.take(5).forEach { Box(Modifier.weight(1f)) { AppCell(it, icons, null, onLaunch, onMenu, iconSize = 52) } }
        repeat(5 - apps.take(5).size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun AppCell(entry: AppEntry, icons: IconCache, profiles: AppCatalog.Snapshot?, onLaunch: (AppEntry, Rect?) -> Unit, onMenu: (AppEntry) -> Unit, iconSize: Int = 56) {
    val paused = entry.profile == ProfileKind.WORK && profiles?.work?.paused == true
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val menuLabel = stringResource(R.string.menu_options)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .onGloballyPositioned { c -> c.boundsInWindow().let { bounds = Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) } }
            .combinedClickable(role = Role.Button, onClick = { onLaunch(entry, bounds) }, onLongClick = { onMenu(entry) }, onLongClickLabel = menuLabel)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(iconSize.dp)) { AppIconTile(icons, entry.key, entry.label, iconSize.dp) }
        Text(
            entry.label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (paused) MasalaTheme.tokens.textLow else MasalaTheme.tokens.textHigh, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun GlobePage(
    apps: List<AppEntry>,
    ranked: List<GlobeApp>,
    usageOrder: Map<String, Int>,
    range: UsageRange,
    onRange: (UsageRange) -> Unit,
    icons: IconCache,
    open: Boolean,
    source: GlobeSource?,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onMenu: (AppEntry) -> Unit,
    profiles: AppCatalog.Snapshot,
    modifier: Modifier,
) {
    val onGlobe = remember(ranked) { ranked.map { it.entry.key.id }.toSet() }
    // Most used first; never-opened apps keep the catalog's A–Z order (the sort is stable).
    val rest = remember(apps, onGlobe, usageOrder) {
        apps.filter { it.key.id !in onGlobe }.sortedBy { usageOrder[it.key.id] ?: Int.MAX_VALUE }
    }
    LazyColumn(modifier.fillMaxWidth()) {
        if (source != null && !source.usageAccess) item(key = "usage") { UsageAccessCard(source.asked, source.onAllow, source.onSkip) }
        item(key = "globe") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (ranked.isEmpty()) {
                    Text(stringResource(R.string.globe_empty), style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.textMedium, modifier = Modifier.padding(24.dp))
                } else {
                    Globe(ranked, icons, "", open, onLaunch = { onLaunch(it, null) }, onMenu = onMenu)
                }
            }
        }
        item(key = "range") { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RangeSwitch(range, onRange) } }
        item(key = "caption") { GlobeCaption() }
        item(key = "more") { GroupHeader(stringResource(R.string.globe_more_apps, rest.size), stringResource(R.string.globe_more_note)) }
        items(rest, key = { it.key.id }) { entry -> AppRow(entry, icons, profiles, labelWork = false, onLaunch, onMenu) }
    }
}

@Composable
private fun AppRow(
    entry: AppEntry,
    icons: IconCache,
    profiles: AppCatalog.Snapshot,
    labelWork: Boolean,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onMenu: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paused = entry.profile == ProfileKind.WORK && profiles.work?.paused == true
    var bounds by remember { mutableStateOf<Rect?>(null) }
    LauncherRow(
        label = entry.label,
        icon = null,
        iconContent = { AppIconTile(icons, entry.key, entry.label, 40.dp) },
        onClick = { onLaunch(entry, bounds) },
        onLongClick = { onMenu(entry) },
        longClickLabel = stringResource(R.string.menu_options),
        subtitle = stringResource(R.string.menu_work_badge).takeIf { labelWork && entry.profile == ProfileKind.WORK },
        dimmed = paused,
        modifier = modifier.onGloballyPositioned { c -> c.boundsInWindow().let { bounds = Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) } },
    )
}

@Composable
private fun WorkHeader(paused: Boolean, onWorkPaused: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Switch) { onWorkPaused(!paused) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.drawer_work_header),
                style = MaterialTheme.typography.titleSmall,
                color = MasalaTheme.tokens.accentText,
                modifier = Modifier.semantics { heading() },
            )
            if (paused) Text(stringResource(R.string.drawer_work_paused), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.drawer_work_toggle), style = MaterialTheme.typography.bodySmall)
        Switch(checked = !paused, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Kept composed and measured, but not placed while [hidden]: an unplaced node isn't drawn or touched,
 * and it doesn't take touches from what's under it the way a hidden but placed one would. Its
 * semantics are cleared too, so screen readers and tests don't find it.
 */
private fun Modifier.hiddenWhen(hidden: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { if (!hidden) placeable.place(0, 0) }
}.then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier)

/**
 * While [enabled], nothing inside gets touches: they're consumed in the first pass, before any child
 * sees them. Home uses it under the open drawer, so taps on the drawer's empty parts can't reach it.
 */
fun Modifier.blockTouches(enabled: Boolean): Modifier = if (!enabled) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
