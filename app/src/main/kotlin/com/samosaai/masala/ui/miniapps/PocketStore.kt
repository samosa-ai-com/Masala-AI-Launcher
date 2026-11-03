package com.samosaai.masala.ui.miniapps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.data.MiniAppEntity
import com.samosaai.masala.masala
import com.samosaai.masala.miniapps.MiniAppIcon
import com.samosaai.masala.miniapps.MiniAppIconView
import com.samosaai.masala.miniapps.PocketApp
import com.samosaai.masala.miniapps.PocketCatalog
import com.samosaai.masala.miniapps.PocketStoreLogic
import com.samosaai.masala.ui.settings.SettingsToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The store's tabs: two browsable homes, a search screen and the user's own library. */
enum class StoreTab { GAMES, APPS, SEARCH, MY_APPS }

/** What the detail screen shows: a bundled pack app, or a user-created app by row id. */
sealed interface StoreDetail {
    data class Bundled(val app: PocketApp) : StoreDetail

    data class Mine(val id: String) : StoreDetail
}

/**
 * The mini-app store (Settings → Mini apps), laid out like a Play Store home: a bottom bar with
 * Games / Apps / Search / My apps, browsable tab homes (filter pills, a wide-card hero carousel,
 * a compact featured row, then titled list sections), a full-screen detail page per app, and the
 * user's own created apps in the same visual language under My apps.
 *
 * No WebView per card and no HTML reads while browsing, so 317 entries scroll smoothly; cards
 * carry an icon and a title only, never a description or a rating.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketStorePage(
    onBack: () -> Unit,
    onOpenBundled: (PocketApp) -> Unit,
    onCustomize: (PocketApp) -> Unit,
    onOpenMine: (String) -> Unit,
    onAskCreate: () -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<PocketApp>>(emptyList()) }
    val mineApps by remember { context.masala.miniApps.all }.collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable { mutableStateOf(StoreTab.GAMES) }
    var lastBrowse by rememberSaveable { mutableStateOf(StoreTab.GAMES) }
    // The picked app isn't saveable (no Parcelable); a process restart just closes the detail.
    var detail by remember { mutableStateOf<StoreDetail?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { PocketCatalog.cached(context) }
    }

    BackHandler {
        when {
            detail != null -> detail = null
            tab == StoreTab.SEARCH || tab == StoreTab.MY_APPS -> tab = lastBrowse
            else -> onBack()
        }
    }

    val title = when {
        detail is StoreDetail.Bundled -> (detail as StoreDetail.Bundled).app.title
        detail is StoreDetail.Mine -> mineApps.firstOrNull { it.id == (detail as StoreDetail.Mine).id }?.title
            ?: stringResource(R.string.pocket_store_my_apps)
        tab == StoreTab.GAMES -> stringResource(R.string.pocket_store_games)
        tab == StoreTab.APPS -> stringResource(R.string.pocket_store_apps)
        tab == StoreTab.SEARCH -> stringResource(R.string.pocket_store_tab_search)
        else -> stringResource(R.string.pocket_store_my_apps)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (detail != null) detail = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            // The detail page is full-screen Play-style: no bottom bar, Back returns to the tab.
            if (detail == null) {
                StoreBottomBar(tab, onSelect = {
                    tab = it
                    if (it == StoreTab.GAMES || it == StoreTab.APPS) lastBrowse = it
                })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                detail is StoreDetail.Bundled -> {
                    val app = (detail as StoreDetail.Bundled).app
                    BundledDetailScreen(
                        app = app,
                        onOpen = { onOpenBundled(app) },
                        onCustomize = { onCustomize(app) },
                    )
                }
                detail is StoreDetail.Mine -> {
                    val id = (detail as StoreDetail.Mine).id
                    val row = mineApps.firstOrNull { it.id == id }
                    if (row == null) {
                        LaunchedEffect(id) { detail = null }
                    } else {
                        MineDetailScreen(
                            row = row,
                            onOpen = { onOpenMine(row.id) },
                            onDeleted = { detail = null },
                        )
                    }
                }
                tab == StoreTab.GAMES || tab == StoreTab.APPS -> {
                    val category = if (tab == StoreTab.GAMES) "games" else "apps"
                    StoreHome(
                        category = category,
                        apps = apps,
                        onPick = { detail = StoreDetail.Bundled(it) },
                        onOpen = onOpenBundled,
                    )
                }
                tab == StoreTab.SEARCH -> SearchTab(
                    apps = apps,
                    onPick = { detail = StoreDetail.Bundled(it) },
                )
                else -> MyAppsTab(
                    mineApps = mineApps,
                    onPick = { detail = StoreDetail.Mine(it.id) },
                    onAskCreate = onAskCreate,
                )
            }
        }
    }
}

/**
 * The bottom navigation bar: four tabs with a pill indicator behind the selected icon, Play-style.
 * A custom row rather than `NavigationBarItem` (which this M3 artifact doesn't expose).
 */
@Composable
private fun StoreBottomBar(current: StoreTab, onSelect: (StoreTab) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StoreBottomTab(current, StoreTab.GAMES, stringResource(R.string.pocket_store_games), Icons.Rounded.SportsEsports, onSelect)
            StoreBottomTab(current, StoreTab.APPS, stringResource(R.string.pocket_store_apps), Icons.Rounded.Apps, onSelect)
            StoreBottomTab(current, StoreTab.SEARCH, stringResource(R.string.pocket_store_tab_search), Icons.Rounded.Search, onSelect)
            StoreBottomTab(current, StoreTab.MY_APPS, stringResource(R.string.pocket_store_my_apps), Icons.Rounded.Person, onSelect)
        }
    }
}

@Composable
private fun RowScope.StoreBottomTab(
    current: StoreTab,
    tab: StoreTab,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onSelect: (StoreTab) -> Unit,
) {
    val selected = current == tab
    Column(
        modifier = Modifier.weight(1f).clickable(onClick = { onSelect(tab) })
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier.width(64.dp).height(32.dp).clip(RoundedCornerShape(50))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One browsable tab home: a pill row (Top + the tab's biggest groups), a hero carousel of wide
 * cards, a compact featured row with Open buttons, then titled list sections. Picking a pill
 * swaps the shelves for that group's full app list.
 */
@Composable
private fun StoreHome(
    category: String,
    apps: List<PocketApp>,
    onPick: (PocketApp) -> Unit,
    onOpen: (PocketApp) -> Unit,
) {
    var pill by rememberSaveable(category) { mutableStateOf("") }
    val shelves = remember(apps, category) { PocketStoreLogic.tabShelves(apps, category) }
    // A pill from the other tab never filters silently: it resets when it isn't listed.
    LaunchedEffect(shelves) {
        if (pill.isNotEmpty() && !shelves.pills.contains(pill)) pill = ""
    }
    val topLabel = stringResource(
        if (category == "games") R.string.pocket_store_top_games else R.string.pocket_store_top_apps,
    )
    val categoryLabel = stringResource(
        if (category == "games") R.string.pocket_store_games else R.string.pocket_store_apps,
    )
    val featuredLabel = stringResource(R.string.pocket_store_featured)
    val shown = remember(apps, category, pill) { PocketStoreLogic.pillApps(apps, category, pill) }
    val list = rememberLazyListState()

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "pills-$category") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "top") {
                    FilterChip(
                        selected = pill.isEmpty(),
                        onClick = { pill = "" },
                        label = { Text(topLabel) },
                        shape = RoundedCornerShape(50),
                    )
                }
                items(shelves.pills, key = { it }) { group ->
                    FilterChip(
                        selected = pill == group,
                        onClick = { pill = if (pill == group) "" else group },
                        label = { Text(PocketStoreLogic.pretty(group)) },
                        shape = RoundedCornerShape(50),
                    )
                }
            }
        }
        if (apps.isEmpty()) return@LazyColumn // first frame, the catalogue is still loading
        if (pill.isNotEmpty()) {
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.pocket_store_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                items(shown, key = { it.id }) { app ->
                    StoreRow(
                        title = app.title,
                        meta = "${PocketStoreLogic.pretty(app.group)} · ${app.players}",
                        icon = { EmojiTile(app.icon.ifBlank { "✨" }, app.id, 56.dp) },
                        badge = stringResource(R.string.pocket_store_included_badge),
                        onClick = { onPick(app) },
                    )
                }
            }
            return@LazyColumn
        }
        item(key = "hero") { HeroCarousel(shelves.hero, onPick) }
        if (shelves.featured.isNotEmpty()) {
            item(key = "featured-h") { SectionHeader(featuredLabel) }
            items(shelves.featured, key = { "f-${it.id}" }) { app ->
                FeaturedRow(
                    app = app,
                    meta = "$categoryLabel · ${app.players}",
                    onPick = { onPick(app) },
                    onOpen = { onOpen(app) },
                )
            }
        }
        shelves.sections.forEach { section ->
            item(key = "h-${section.group}") { SectionHeader(section.title) }
            items(section.apps, key = { "s-${it.id}" }) { app ->
                StoreRow(
                    title = app.title,
                    meta = "${PocketStoreLogic.pretty(app.group)} · ${app.players}",
                    icon = { EmojiTile(app.icon.ifBlank { "✨" }, app.id, 56.dp) },
                    badge = stringResource(R.string.pocket_store_included_badge),
                    onClick = { onPick(app) },
                )
            }
        }
    }
}

/** Wide horizontally scrolling cards: a badge chip and the app title over a hued gradient. */
@Composable
private fun HeroCarousel(hero: List<PocketApp>, onPick: (PocketApp) -> Unit) {
    if (hero.isEmpty()) return
    val dark = isSystemInDarkTheme()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(hero, key = { "hero-${it.id}" }) { app ->
            val hue = remember(app.id) { PocketStoreLogic.tileHue(app.id) }
            val ground = remember(hue, dark) {
                Brush.linearGradient(
                    listOf(
                        Color.hsl(hue, 0.62f, if (dark) 0.34f else 0.82f),
                        Color.hsl((hue + 40f) % 360f, 0.62f, if (dark) 0.22f else 0.68f),
                    ),
                )
            }
            Box(
                Modifier.width(280.dp).height(152.dp).clip(RoundedCornerShape(24.dp)).background(ground)
                    .clickable(onClick = { onPick(app) }).padding(16.dp),
            ) {
                Text(
                    app.players.ifBlank { "✨" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.TopStart)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    maxLines = 1,
                )
                Text(
                    app.icon.ifBlank { "✨" },
                    fontSize = 64.sp,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                )
                Text(
                    app.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (dark) Color.White else Color(0xFF101418),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.72f),
                )
            }
        }
    }
}

/** A titled list block header. */
@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/**
 * The compact featured row: a 56 dp icon, the title, a metadata line and an Open pill button —
 * no description. Tapping the row opens the detail page; Open launches straight in.
 */
@Composable
private fun FeaturedRow(app: PocketApp, meta: String, onPick: () -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmojiTile(app.icon.ifBlank { "✨" }, app.id, 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(app.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onOpen, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
            Text(stringResource(R.string.miniapp_open))
        }
    }
}

/** A list row: a 56 dp icon, the title, a metadata line and a small badge — no description. */
@Composable
private fun StoreRow(
    title: String,
    meta: String,
    icon: @Composable () -> Unit,
    badge: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!badge.isNullOrBlank()) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** Search is a field over a result list in the same row style as the shelves. */
@Composable
private fun SearchTab(apps: List<PocketApp>, onPick: (PocketApp) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(apps, query) { PocketStoreLogic.search(apps, query) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "field") {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text(stringResource(R.string.pocket_store_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (query.isBlank()) return@LazyColumn
        if (results.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.pocket_store_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
            return@LazyColumn
        }
        item(key = "count") {
            Text(
                stringResource(R.string.pocket_store_count, results.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        items(results, key = { "q-${it.id}" }) { app ->
            val categoryLabel = stringResource(
                if (app.category == "games") R.string.pocket_store_games else R.string.pocket_store_apps,
            )
            StoreRow(
                title = app.title,
                meta = "$categoryLabel · ${PocketStoreLogic.pretty(app.group)}",
                icon = { EmojiTile(app.icon.ifBlank { "✨" }, app.id, 56.dp) },
                badge = null,
                onClick = { onPick(app) },
            )
        }
    }
}

/** The user's own library: created apps in the same row style, plus a create entry point. */
@Composable
private fun MyAppsTab(
    mineApps: List<MiniAppEntity>,
    onPick: (MiniAppEntity) -> Unit,
    onAskCreate: () -> Unit,
) {
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    val mineLabel = stringResource(R.string.menu_miniapp_subtitle)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "create") {
            Button(
                onClick = onAskCreate,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.miniapp_create)) }
        }
        if (mineApps.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.miniapps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            return@LazyColumn
        }
        items(mineApps, key = { "m-${it.id}" }) { row ->
            StoreRow(
                title = row.title,
                meta = "$mineLabel · ${format.format(java.util.Date(row.updatedAt))}",
                icon = { MiniAppIconView(row.icon, row.title, 56.dp) },
                badge = null,
                onClick = { onPick(row) },
            )
        }
    }
}

/**
 * The bundled detail page: a large tinted header block instead of screenshots (there are no real
 * ones to show), the icon, title and metadata, the description, and Open + Customise buttons.
 */
@Composable
private fun BundledDetailScreen(app: PocketApp, onOpen: () -> Unit, onCustomize: () -> Unit) {
    val categoryLabel = stringResource(
        if (app.category == "games") R.string.pocket_store_games else R.string.pocket_store_apps,
    )
    val dark = isSystemInDarkTheme()
    val hue = remember(app.id) { PocketStoreLogic.tileHue(app.id) }
    val ground = remember(hue, dark) {
        Brush.linearGradient(
            listOf(
                Color.hsl(hue, 0.62f, if (dark) 0.34f else 0.82f),
                Color.hsl((hue + 40f) % 360f, 0.62f, if (dark) 0.22f else 0.68f),
            ),
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp)).background(ground),
            contentAlignment = Alignment.Center,
        ) {
            Text(app.icon.ifBlank { "✨" }, fontSize = 72.sp, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            EmojiTile(app.icon.ifBlank { "✨" }, app.id, 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(categoryLabel, PocketStoreLogic.pretty(app.group), app.players)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (app.description.isNotBlank()) {
            Text(app.description, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            stringResource(R.string.pocket_store_included),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpen) { Text(stringResource(R.string.miniapp_open)) }
            OutlinedButton(onClick = onCustomize) { Text(stringResource(R.string.pocket_store_customize)) }
        }
    }
}

/**
 * The user-app detail page: the same header language as a bundled page, then the library
 * actions — Rename, Icon, Media permission, Pin to home and Delete.
 */
@Composable
private fun MineDetailScreen(row: MiniAppEntity, onOpen: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.masala }
    val scope = rememberCoroutineScope()
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    var renameOpen by rememberSaveable(row.id) { mutableStateOf(false) }
    var iconOpen by rememberSaveable(row.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(row.id) { mutableStateOf(false) }
    var mediaAllowed by remember(row.id, row.mediaAllowed) { mutableStateOf(row.mediaAllowed) }
    val dark = isSystemInDarkTheme()
    val hue = remember(row.id) { PocketStoreLogic.tileHue(row.id) }
    val ground = remember(hue, dark) {
        Brush.linearGradient(
            listOf(
                Color.hsl(hue, 0.62f, if (dark) 0.34f else 0.82f),
                Color.hsl((hue + 40f) % 360f, 0.62f, if (dark) 0.22f else 0.68f),
            ),
        )
    }
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    val pinnedText = stringResource(R.string.miniapp_pinned)
    val pinFailedText = stringResource(R.string.miniapp_pin_failed)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp)).background(ground),
            contentAlignment = Alignment.Center,
        ) {
            MiniAppIconView(row.icon, row.title, 72.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MiniAppIconView(row.icon, row.title, 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${stringResource(R.string.menu_miniapp_subtitle)} · ${format.format(java.util.Date(row.updatedAt))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpen) { Text(stringResource(R.string.miniapp_open)) }
        }
        SettingsToggleRow(
            label = stringResource(R.string.miniapp_media),
            checked = mediaAllowed,
            summary = stringResource(R.string.miniapp_media_summary),
            onCheckedChange = { checked ->
                mediaAllowed = checked
                scope.launch { app.miniApps.setMediaAllowed(row.id, checked) }
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { renameOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.menu_rename), modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { iconOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.menu_change_icon), modifier = Modifier.fillMaxWidth())
            }
            TextButton(
                onClick = {
                    scope.launch {
                        val problems = app.layouts.pinMiniApp(row.id, row.title)
                        toast(if (problems.isEmpty()) pinnedText else pinFailedText)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.miniapp_pin), modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_version_delete),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (renameOpen) {
        var name by remember(row.id, row.title) { mutableStateOf(row.title) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(stringResource(R.string.item_rename_title, row.title)) },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        renameOpen = false
                        scope.launch { app.miniApps.rename(row.id, name) }
                    },
                ) { Text(stringResource(R.string.menu_rename)) }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text(stringResource(R.string.action_back)) } },
        )
    }
    if (iconOpen) {
        var spec by remember(row.id, row.icon) { mutableStateOf(row.icon.orEmpty()) }
        var bad by remember(row.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { iconOpen = false },
            title = { Text(stringResource(R.string.icon_picker_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        spec, { spec = it; bad = false },
                        label = { Text(stringResource(R.string.icon_picker_custom)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (bad) {
                        Text(
                            stringResource(R.string.icon_picker_bad_svg),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Blank resets to the title letter; anything else must sanitize.
                        if (spec.isBlank() || MiniAppIcon.sanitize(spec) != null) {
                            iconOpen = false
                            scope.launch { app.miniApps.setIcon(row.id, spec.ifBlank { null }) }
                        } else {
                            bad = true
                        }
                    },
                ) { Text(stringResource(R.string.icon_picker_use_svg)) }
            },
            dismissButton = { TextButton(onClick = { iconOpen = false }) { Text(stringResource(R.string.action_back)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.miniapp_delete_title)) },
            text = { Text(stringResource(R.string.miniapp_delete_message, row.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch { app.deleteMiniApp(row.id) }
                        onDeleted()
                    },
                ) { Text(stringResource(R.string.settings_version_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_back)) } },
        )
    }
}

/** The catalogue's emoji on a stable coloured tile until real icons ship with the pack. */
@Composable
internal fun EmojiTile(emoji: String, id: String, size: Dp) {    val dark = isSystemInDarkTheme()
    val ground = remember(id, dark) {
        Color.hsl(PocketStoreLogic.tileHue(id), 0.55f, if (dark) 0.30f else 0.88f)
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(18.dp)).background(ground),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Normal, maxLines = 1)
    }
}
