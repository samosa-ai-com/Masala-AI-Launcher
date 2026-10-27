package com.samosaai.masala.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samosaai.masala.R
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.GroupSection
import com.samosaai.masala.core.contract.HeaderSection
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.ListSection
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.Section
import com.samosaai.masala.core.contract.SkillWidgetSection
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.layout.ResolvedLayout
import com.samosaai.masala.launcher.IconCache
import com.samosaai.masala.launcher.rememberAppIcon
import com.samosaai.masala.ui.components.wallpaperText
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the renderer needs from the screen around it. */
class RenderContext(
    val icons: IconCache,
    /** Every launchable app, to find an item's icon and the user's renames. */
    val apps: List<AppEntry>,
    val now: LocalDateTime,
    val locked: Boolean,
    val onAction: (MasalaAction) -> Unit,
    val onAppMenu: (AppEntry) -> Unit,
    val onClockClick: () -> Unit,
)

/**
 * Draws a [ResolvedLayout] (plan Phase 2): a pager of pages; on each page the sections in a column, the
 * dock pinned to the bottom. One composable per section type; widget, stack and skill-widget sections are
 * placeholders until Phases 3, 4 and 6. Grid columns follow the width and the text size, every target is
 * at least 48dp, and every item reads out its label.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LayoutRenderer(
    layout: ResolvedLayout,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    /** Bumped when Home is pressed while home shows: back to the first page. */
    resetSignal: Int = 0,
    footer: @Composable () -> Unit = {},
) {
    val pages = layout.layout.pages
    val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
    LaunchedEffect(resetSignal) { if (resetSignal > 0) pager.animateScrollToPage(0) }
    Column(modifier) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { index ->
            pages.getOrNull(index)?.let { PageContent(it, layout, ctx, footer = if (index == pages.lastIndex) footer else ({})) }
        }
        if (pages.size > 1) PageDots(pages.size, pager.currentPage)
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier.padding(4.dp).size(8.dp).clip(CircleShape)
                    .background(if (i == current) Color.White else Color.White.copy(alpha = 0.4f)),
            )
        }
    }
}

@Composable
private fun PageContent(page: Page, layout: ResolvedLayout, ctx: RenderContext, footer: @Composable () -> Unit) {
    val (docks, body) = page.sections.partition { it is DockSection }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            body.forEach { section ->
                item(key = section.id) { SectionContent(section, layout, ctx) }
            }
            item(key = "footer") { footer() }
        }
        docks.forEach { SectionContent(it, layout, ctx) }
    }
}

@Composable
private fun SectionContent(section: Section, layout: ResolvedLayout, ctx: RenderContext) {
    when (section) {
        is HeaderSection -> Header(section, ctx)
        is GridSection -> ItemGrid(section.title, section.items, section.columns, ctx)
        is DockSection -> Dock(section, ctx)
        is ListSection -> ItemList(section.title, section.items, ctx)
        is GroupSection -> Group(section, ctx)
        is WidgetSection -> Placeholder(section.widget.title ?: section.widget.id, stringResource(R.string.home_widget_placeholder), layout.widgetRows[section.widget.id])
        is StackSection -> section.widgets.firstOrNull()?.let {
            Placeholder(it.title ?: it.id, stringResource(R.string.home_stack_placeholder, section.widgets.size), layout.widgetRows[it.id])
        }
        is SkillWidgetSection -> Placeholder(section.skillId, stringResource(R.string.home_skill_widget_placeholder), null)
    }
}

// ---- header ----

@Composable
private fun Header(section: HeaderSection, ctx: RenderContext) {
    val time = remember(ctx.now.hour, ctx.now.minute) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(ctx.now) }
    val date = remember(ctx.now.toLocalDate()) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(ctx.now) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        if (section.showClock) {
            Text(
                time,
                style = wallpaperText(MaterialTheme.typography.displayLarge),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = ctx.onClockClick)
                    .semantics { contentDescription = time },
            )
        }
        if (section.showDate) Text(date, style = wallpaperText(MaterialTheme.typography.titleMedium))
    }
}

// ---- items ----

/** Columns that fit: the section's own number, fewer when the screen is narrow or the text is large. */
@Composable
private fun columnsFor(wanted: Int, maxWidthDp: Float): Int {
    val scale = LocalDensity.current.fontScale
    val cell = 76f * scale.coerceAtLeast(1f)
    return minOf(wanted.coerceAtLeast(1), (maxWidthDp / cell).toInt().coerceAtLeast(1))
}

@Composable
private fun ItemGrid(title: String?, items: List<Item>, columns: Int, ctx: RenderContext) {
    if (items.isEmpty() && title == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        title?.let { Text(it, style = wallpaperText(MaterialTheme.typography.titleSmall), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = columnsFor(columns, maxWidth.value)
            Column {
                items.chunked(cols).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { item -> Box(Modifier.weight(1f)) { ItemCell(item, ctx) } }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Dock(section: DockSection, ctx: RenderContext) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        section.items.forEach { item -> Box(Modifier.weight(1f)) { ItemCell(item, ctx, showLabel = false) } }
    }
}

@Composable
private fun ItemList(title: String?, items: List<Item>, ctx: RenderContext) {
    Column(Modifier.fillMaxWidth()) {
        title?.let { Text(it, style = wallpaperText(MaterialTheme.typography.titleSmall), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        items.forEach { item -> ItemRow(item, ctx) }
    }
}

@Composable
private fun Group(section: GroupSection, ctx: RenderContext) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open }.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(section.label, style = wallpaperText(MaterialTheme.typography.titleMedium), modifier = Modifier.weight(1f))
            Text(if (open) "▾" else "▸", style = wallpaperText(MaterialTheme.typography.titleMedium))
        }
        if (open) ItemGrid(null, section.items, 4, ctx)
    }
}

@Composable
private fun ItemCell(item: Item, ctx: RenderContext, showLabel: Boolean = true) {
    val app = appFor(item, ctx)
    val label = app?.takeIf { it.renamed }?.label ?: item.label
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .itemClicks(item, app, ctx, label)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconView(item.icon, app, ctx, size = 48)
        if (showLabel) {
            Text(
                label,
                style = wallpaperText(MaterialTheme.typography.labelMedium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

@Composable
private fun ItemRow(item: Item, ctx: RenderContext) {
    val app = appFor(item, ctx)
    val label = app?.takeIf { it.renamed }?.label ?: item.label
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).itemClicks(item, app, ctx, label).padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconView(item.icon, app, ctx, size = 40)
        Text(label, style = wallpaperText(MaterialTheme.typography.titleMedium), modifier = Modifier.padding(start = 16.dp))
    }
}

/** Tap runs the action; a long press on a pinned app opens its menu unless the home layout is locked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.itemClicks(item: Item, app: AppEntry?, ctx: RenderContext, label: String): Modifier {
    val menuLabel = stringResource(R.string.menu_options)
    val canMenu = app != null && !ctx.locked
    return this
        .combinedClickable(
            onClick = { ctx.onAction(item.action) },
            onLongClick = if (canMenu) ({ ctx.onAppMenu(app!!) }) else null,
            onLongClickLabel = if (canMenu) menuLabel else null,
        )
        .semantics { contentDescription = label; role = Role.Button }
}

private fun appFor(item: Item, ctx: RenderContext): AppEntry? {
    val a = item.action as? MasalaAction.LaunchApp ?: return null
    return ctx.apps.firstOrNull { it.key.packageName == a.packageName && it.key.user == a.user && (a.activity == null || it.key.activity == a.activity) }
}

@Composable
private fun IconView(icon: IconRef, app: AppEntry?, ctx: RenderContext, size: Int) {
    when (icon) {
        is IconRef.AppIcon -> {
            // The same app in another profile has its own key and badge.
            val entry = app ?: ctx.apps.firstOrNull { it.key.packageName == icon.packageName && it.key.user == icon.user }
            val bitmap = entry?.let { rememberAppIcon(ctx.icons, it.key) }
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, modifier = Modifier.size(size.dp))
            } else {
                SymbolDisc("?", null, size)
            }
        }
        is IconRef.Symbol -> SymbolDisc(SymbolIcons.glyph(icon.name), SymbolIcons.tint(icon.tint), size)
        is IconRef.Override -> SymbolDisc("•", null, size)
    }
}

@Composable
private fun SymbolDisc(glyph: String, tint: Color?, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background((tint ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.85f))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = (size * 0.45f).sp, color = Color.White)
    }
}

@Composable
private fun Placeholder(title: String, note: String, rows: Int?) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = ((rows ?: 2) * 48).dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
