package com.samosaai.masala.ui.miniapps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.samosaai.masala.R
import com.samosaai.masala.miniapps.PocketApp
import com.samosaai.masala.miniapps.PocketCatalog
import com.samosaai.masala.miniapps.PocketStoreLogic
import com.samosaai.masala.ui.settings.SettingsScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bundled mini-app store (Settings → Mini apps → Store), laid out like a storefront rather than
 * a list: a featured row, then one horizontally scrolling shelf per shelf-worthy category, and a
 * search field that flips to a result grid. Cards carry an icon and a title only — a description in
 * every tile is what made the first version read like a document. The full text lives in the detail
 * sheet, where Open (straight from the pack, offline) and Customise with Masala sit.
 *
 * No WebView per card and no HTML reads while browsing, so 317 entries scroll smoothly.
 */
@Composable
fun PocketStorePage(
    onBack: () -> Unit,
    onOpen: (PocketApp) -> Unit,
    onCustomize: (PocketApp) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<PocketApp>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<PocketApp?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { PocketCatalog.cached(context) }
    }

    val groups = remember(apps, category) { PocketStoreLogic.groups(apps, category) }
    // A group chip from another tab never filters silently: it resets when it isn't listed.
    LaunchedEffect(groups) {
        if (group.isNotEmpty() && !groups.contains(group)) group = ""
    }
    val shown = remember(apps, query, category, group) {
        PocketStoreLogic.filter(apps, query, category, group)
    }
    val browsing = query.isBlank() && category.isEmpty() && group.isEmpty()

    SettingsScaffold(stringResource(R.string.pocket_store_title), onBack) {
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text(stringResource(R.string.pocket_store_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = category.isEmpty(), onClick = { category = "" }, label = { Text(stringResource(R.string.wallpaper_chip_all)) })
            FilterChip(selected = category == "apps", onClick = { category = "apps" }, label = { Text(stringResource(R.string.pocket_store_apps)) })
            FilterChip(selected = category == "games", onClick = { category = "games" }, label = { Text(stringResource(R.string.pocket_store_games)) })
        }
        if (groups.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { g ->
                    FilterChip(selected = group == g, onClick = { group = if (group == g) "" else g }, label = { Text(groupLabel(g)) })
                }
            }
        }
        when {
            apps.isEmpty() -> Unit // first frame, the catalogue is still loading
            browsing -> StoreShelves(apps, onPick = { picked = it }, modifier = Modifier.weight(1f))
            shown.isEmpty() -> Text(
                stringResource(R.string.pocket_store_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            else -> {
                Text(
                    stringResource(R.string.pocket_store_count, shown.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(shown, key = { it.id }) { app ->
                        AppTile(app, onClick = { picked = app })
                    }
                }
            }
        }
    }

    picked?.let { app ->
        PocketDetailSheet(
            app = app,
            onOpen = { picked = null; onOpen(app) },
            onCustomize = { picked = null; onCustomize(app) },
            onDismiss = { picked = null },
        )
    }
}

/**
 * The storefront proper: a featured shelf, a games shelf and then the biggest categories. Grouped
 * once per catalogue load; a shelf never repeats an app to keep the rows distinct.
 */
@Composable
private fun StoreShelves(apps: List<PocketApp>, onPick: (PocketApp) -> Unit, modifier: Modifier = Modifier) {
    val shelves = remember(apps) { PocketStoreLogic.shelves(apps) }
    val featuredTitle = stringResource(R.string.pocket_store_featured)
    val topGamesTitle = stringResource(R.string.pocket_store_top_games)
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        shelves.forEach { shelf ->
            val title = when (shelf.kind) {
                PocketStoreLogic.ShelfKind.FEATURED -> featuredTitle
                PocketStoreLogic.ShelfKind.TOP_GAMES -> topGamesTitle
                PocketStoreLogic.ShelfKind.GROUP -> shelf.title
            }
            item(key = shelf.kind.name + shelf.title) {
                Shelf(title, shelf.apps, onPick)
            }
        }
    }
}

/** One titled row: a header and a horizontally scrolling line of tiles. */
@Composable
private fun Shelf(title: String, apps: List<PocketApp>, onPick: (PocketApp) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(apps, key = { it.id }) { app ->
                AppTile(app, onClick = { onPick(app) }, modifier = Modifier.width(112.dp))
            }
        }
    }
}

/** Icon over a one-line title: the store's card. No description by design. */
@Composable
private fun AppTile(app: PocketApp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.width(104.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmojiTile(app.icon.ifBlank { "✨" }, app.id, 64.dp)
        Text(
            app.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** `word-guessing` → `Word guessing`; the catalogue stores machine names. */
internal fun groupLabel(group: String): String =
    group.split('-', '_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

@Composable
private fun PocketDetailSheet(
    app: PocketApp,
    onOpen: () -> Unit,
    onCustomize: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EmojiTile(app.icon.ifBlank { "✨" }, app.id, 64.dp)
                Column(Modifier.weight(1f)) {
                    Text(app.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOf(groupLabel(app.group), app.players).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (app.description.isNotBlank()) {
                Text(app.description, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.pocket_store_included),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text(stringResource(R.string.miniapp_open)) }
                TextButton(onClick = onCustomize) { Text(stringResource(R.string.pocket_store_customize)) }
            }
        }
    }
}

/** The catalogue's emoji on a stable coloured tile until real icons ship with the pack. */
@Composable
internal fun EmojiTile(emoji: String, id: String, size: androidx.compose.ui.unit.Dp) {
    val dark = isSystemInDarkTheme()
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
