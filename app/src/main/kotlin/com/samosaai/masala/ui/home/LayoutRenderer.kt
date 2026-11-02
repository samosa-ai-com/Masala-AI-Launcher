package com.samosaai.masala.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.samosaai.masala.ui.theme.OnWallpaper
import com.samosaai.masala.ui.theme.MasalaTheme
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.unit.round
import com.samosaai.masala.ui.theme.rememberHaptics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.ui.platform.LocalContext
import com.samosaai.masala.masala
import com.samosaai.masala.ui.components.IconTile
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
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
import com.samosaai.masala.miniapps.MiniAppIconView
import com.samosaai.masala.miniapps.SpecIcon
import com.samosaai.masala.core.layout.ResolvedLayout
import com.samosaai.masala.launcher.IconCache
import com.samosaai.masala.launcher.rememberAppIcon
import com.samosaai.masala.ui.components.wallpaperText
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the renderer needs from the screen around it. Stable and remembered by the caller, so a minute
 * tick or an unrelated state change doesn't recompose the whole layout: only the clock reads [clock].
 */
@Stable
class RenderContext(
    val icons: IconCache,
    /** Every launchable app, to find an item's icon and the user's renames. */
    val apps: List<AppEntry>,
    /** The current time, read only by the clock so the tick recomposes the clock alone. */
    val clock: () -> LocalDateTime,
    val locked: Boolean,
    val onAction: (MasalaAction) -> Unit,
    val onAppMenu: (AppEntry) -> Unit,
    val onClockClick: () -> Unit,
    /** Where skill widgets get their skill and latest result; without it they stay placeholders. */
    val skillWidgets: SkillWidgetSource? = null,
    /** Where generated widgets get their snapshot, stored values and actions; none keeps the placeholder. */
    val widgets: com.samosaai.masala.ui.home.widgets.WidgetSource? = null,
    /** Long-press on a home widget opens its menu (remove, resize, edit in manager). */
    val onWidgetMenu: ((com.samosaai.masala.core.contract.WidgetSpec, String) -> Unit)? = null,
    /** The active mode's name for the chip above the clock (UI refresh U3); none hides the chip. */
    val modeName: String? = null,
    val onModeChip: (() -> Unit)? = null,
    /** Opens an app from the bounds of its icon on screen, so it grows out of it. */
    val onLaunchFrom: ((MasalaAction, android.graphics.Rect) -> Unit)? = null,
    /** Long-press-then-drag to move items; none keeps the plain long-press menu. */
    val drag: HomeDragState? = null,
    /** Ids of the items in the base layout: only these can be dragged (not mode-added ones). */
    val movable: Set<String> = emptySet(),
    /** The menu for an item that isn't an app; apps use [onAppMenu]. */
    val onItemMenu: ((Item) -> Unit)? = null,
    /** A mini app's stored icon by id; null draws the pinned item's own icon. */
    val miniAppIcon: (String) -> String? = { null },
    /** A mini app's document for HTML widgets; none keeps the placeholder. */
    val htmlWidget: (String) -> com.samosaai.masala.data.MiniAppEntity? = { null },
    /** Tapping a folder (an `open_folder` item) shows its items; folders are never dispatched. */
    val onOpenFolder: ((Item) -> Unit)? = null,
    /** A folder's own menu (rename, ungroup). */
    val onFolderMenu: ((Item) -> Unit)? = null,
    val onDrop: ((Item, DropTarget) -> Unit)? = null,
    /** Long-press-then-drag to reorder a widget section on its page; none keeps the menu only. */
    val onWidgetDrop: ((sectionId: String, target: SectionDrop) -> Unit)? = null,
    /** Whether a section id lives in the base layout (not added by a mode): only those drag. */
    val isBaseSection: (String) -> Boolean = { true },
    /** Long-press-then-drag a widget cell to another spot on its grid; none keeps the menu only. */
    val onCellDrop: ((cellId: String, target: CellDrop) -> Unit)? = null,
    /** The cell showing a resize handle (from the widget menu); null when none. */
    val resizingCell: String? = null,
    /** A handle drag ended at [w] × [h] cells; also called with the old size to just close it. */
    val onCellResize: ((cellId: String, w: Int, h: Int) -> Unit)? = null,
) {
    /** A fixed time, for previews and tests. */
    constructor(
        icons: IconCache,
        apps: List<AppEntry>,
        now: LocalDateTime,
        locked: Boolean,
        onAction: (MasalaAction) -> Unit,
        onAppMenu: (AppEntry) -> Unit,
        onClockClick: () -> Unit,
        skillWidgets: SkillWidgetSource? = null,
    ) : this(icons, apps, { now }, locked, onAction, onAppMenu, onClockClick, skillWidgets)

    /** Apps by package and user, built once per catalog instead of scanning every app per item. */
    private val byPackage: Map<Pair<String, Long>, List<AppEntry>> by lazy {
        apps.groupBy { it.key.packageName to it.key.user }
    }

    fun appFor(packageName: String, user: Long, activity: String?): AppEntry? {
        val candidates = byPackage[packageName to user] ?: return null
        return if (activity == null) candidates.firstOrNull() else candidates.firstOrNull { it.key.activity == activity }
    }
}

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
    /** Drawn above the dock on every page: home's search-or-ask pill (UI refresh U3). */
    aboveDock: @Composable () -> Unit = {},
    /** Bumped when a new layout version is applied: sections arrive one by one (UI refresh U4). */
    entrySignal: Int = 0,
    footer: @Composable () -> Unit = {},
) {
    val pages = layout.layout.pages
    val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
    LaunchedEffect(resetSignal) { if (resetSignal > 0) pager.animateScrollToPage(0) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var width by remember { mutableStateOf(0) }
    val drag = ctx.drag
    val dragging = drag?.dragging != null
    if (drag != null) {
        LaunchedEffect(drag, pages) { snapshotFlow { pager.currentPage }.collect { drag.page = pages.getOrNull(it)?.id } }
        val edge = with(LocalDensity.current) { EDGE_FLIP_ZONE.toPx() }
        LaunchedEffect(drag, dragging, pages.size) {
            if (!dragging) return@LaunchedEffect
            var held = 0L
            while (true) {
                delay(EDGE_TICK_MS)
                val x = drag.pointer.x - origin.x
                val step = when {
                    x < edge -> -1
                    x > width - edge -> 1
                    else -> 0
                }
                val target = pager.currentPage + step
                held = if (step != 0 && target in pages.indices) held + EDGE_TICK_MS else 0L
                if (held >= EDGE_HOLD_MS) {
                    held = 0L
                    pager.animateScrollToPage(target)
                }
            }
        }
    }
    // A generated layout, an undo or a mode turning on can replace the document under a drag: cancel
    // it rather than write an edit against a version the item or section may no longer be in.
    LaunchedEffect(drag, layout.layout, drag?.dragging?.id, drag?.draggingSection, drag?.draggingCell?.id) {
        drag?.dragging?.id?.let { id ->
            val present = layout.layout.pages.any { page ->
                page.sections.filterIsInstance<ItemSection>().any { section -> section.items.any { it.id == id } }
            }
            if (!present) drag.cancel()
        }
        drag?.draggingSection?.let { sid ->
            val present = layout.layout.pages.any { page -> page.sections.any { it.id == sid } }
            if (!present) drag.cancelSection()
        }
        drag?.draggingCell?.let { cell ->
            val present = layout.layout.pages.any { page ->
                page.sections.any { it is com.samosaai.masala.core.contract.CellGridSection && it.cells.any { c -> c.id == cell.id } }
            }
            if (!present) drag.cancelCell()
        }
    }
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot(); width = it.size.width }) {
        Column(Modifier.fillMaxSize()) {
            // While dragging, every page stays composed so the dragged cell's gesture survives a flip.
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = if (dragging) pages.size else 0) { index ->
                pages.getOrNull(index)?.let { PageContent(it, layout, ctx, aboveDock, entrySignal, enabled = index == pager.currentPage, footer = if (index == pages.lastIndex) footer else ({})) }
            }
            if (pages.size > 1) PageDots(pages.size, pager.currentPage)
        }
        drag?.dragging?.let { item ->
            DropIndicator(drag.target(), drag, origin)
            LiftedItem(item, drag, origin, ctx)
        }
        if (drag?.draggingSection != null) {
            SectionDropIndicator(drag.sectionTarget(), drag, origin)
        }
        drag?.draggingCell?.let { cell -> CellDropIndicator(drag.cellTarget(), cell, drag, origin) }
    }
}

/** Where a dragged cell would land: its outline on the grid, at its own size. */
@Composable
private fun CellDropIndicator(target: CellDrop?, cell: com.samosaai.masala.core.contract.Cell, drag: HomeDragState, origin: Offset) {
    val grid = target?.let { drag.grids[it.gridId] } ?: return
    val accent = MasalaTheme.tokens.accent
    Canvas(Modifier.fillMaxSize()) {
        val inset = 4.dp.toPx()
        val left = grid.box.left + target.x * grid.cellWidth - origin.x + inset
        val top = grid.box.top + target.y * grid.cellHeight - origin.y + inset
        val size = Size(cell.w * grid.cellWidth - 2 * inset, cell.h * grid.cellHeight - 2 * inset)
        val corner = androidx.compose.ui.geometry.CornerRadius(26.dp.toPx())
        drawRoundRect(accent.copy(alpha = 0.18f), Offset(left, top), size, corner)
        drawRoundRect(accent, Offset(left, top), size, corner, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

/** The dragged item under the finger, a little larger and raised. */
@Composable
internal fun LiftedItem(item: Item, drag: HomeDragState, origin: Offset, ctx: RenderContext) {
    val width = 80.dp
    val widthPx = with(LocalDensity.current) { width.toPx() }
    Box(
        Modifier
            .offset {
                val grab = Offset(drag.grab.x.coerceAtMost(widthPx / 2), drag.grab.y)
                (drag.pointer - grab - origin).round()
            }
            .width(width)
            .graphicsLayer {
                scaleX = 1.12f
                scaleY = 1.12f
                shadowElevation = 16.dp.toPx()
            },
    ) { ItemCell(item, ctx, sectionId = null, interactive = false) }
}

/**
 * What a drop would do, drawn from the boxes the drag state already holds: a bar where the item would
 * land, a ring round an item it would become a folder with, and a strip on the edge that is about to
 * turn the page. [origin] is this renderer's position in root pixels, the space the boxes are in.
 */
@Composable
private fun DropIndicator(target: DropTarget?, drag: HomeDragState, origin: Offset) {
    if (target == null) return
    val accent = MasalaTheme.tokens.accent
    Canvas(Modifier.fillMaxSize()) {
        val line = 3.dp.toPx()
        when (target) {
            is DropTarget.Slot -> {
                val neighbours = drag.itemBoxes.entries
                    .filter { it.value.first == target.sectionId && it.key != drag.dragging?.id }
                    .sortedWith(compareBy({ it.value.second.top }, { it.value.second.left }))
                val marked = target.before?.let { id -> neighbours.firstOrNull { it.key == id } }
                val box = (marked ?: neighbours.lastOrNull())?.value?.second
                    ?: drag.sectionBoxes[target.sectionId]
                    ?: return@Canvas
                if (box.right - box.left > box.height * 2) {
                    // A full-width row: the bar sits between rows rather than beside one.
                    val y = (if (marked == null) box.bottom else box.top) - origin.y - line / 2
                    drawRoundRect(accent, Offset(box.left - origin.x + 16.dp.toPx(), y), Size(box.right - box.left - 32.dp.toPx(), line))
                } else {
                    val x = (if (marked == null) box.right else box.left) - origin.x - line / 2
                    drawRoundRect(accent, Offset(x, box.top - origin.y + 6.dp.toPx()), Size(line, (box.height - 12.dp.toPx()).coerceAtLeast(line)))
                }
            }
            is DropTarget.Onto -> drag.itemBoxes[target.itemId]?.second?.let { box ->
                drawRoundRect(
                    accent,
                    Offset(box.left - origin.x, box.top - origin.y),
                    Size(box.right - box.left, box.height),
                    CornerRadius(18.dp.toPx()),
                    style = Stroke(line),
                )
            }
            // The side the pointer is held against, waiting for the flip.
            is DropTarget.Page -> {
                val left = drag.pointer.x - origin.x < size.width / 2
                drawRoundRect(accent, Offset(if (left) 0f else size.width - line, 0f), Size(line, size.height))
            }
            DropTarget.OutOfFolder -> Unit
        }
    }
}

/** Where a dragged widget section would land: a bar across its page above the next sibling. */
@Composable
private fun SectionDropIndicator(target: SectionDrop?, drag: HomeDragState, origin: Offset) {
    if (target == null) return
    val accent = MasalaTheme.tokens.accent
    Canvas(Modifier.fillMaxSize()) {
        val line = 3.dp.toPx()
        val boxes = drag.sectionBoxes.entries
            .filter { (id, _) -> id != drag.draggingSection && drag.sectionPages[id] == target.pageId }
            .sortedBy { it.value.top }
        val box = target.beforeSectionId?.let { id -> boxes.firstOrNull { it.key == id }?.value }
            ?: boxes.lastOrNull()?.value
            ?: return@Canvas
        val y = when {
            target.beforeSectionId != null -> box.top
            // A null before lands before the dock (moveSection caps there): draw at its top.
            boxes.any { it.key == "dock" } -> boxes.first { it.key == "dock" }.value.top
            else -> box.bottom
        } - origin.y - line / 2
        drawRoundRect(accent, Offset(16.dp.toPx(), y), Size(size.width - 32.dp.toPx(), line))
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
private fun PageContent(page: Page, layout: ResolvedLayout, ctx: RenderContext, aboveDock: @Composable () -> Unit, entrySignal: Int, enabled: Boolean = true, footer: @Composable () -> Unit = {}) {
    val (docks, body) = page.sections.partition { it is DockSection }
    val listState = rememberLazyListState()
    var viewport by remember { mutableStateOf(Box2(0f, 0f, 0f, 0f)) }
    val drag = ctx.drag
    val dragging = (drag?.dragging != null || drag?.draggingSection != null) && drag.page == page.id
    val bandPx = with(LocalDensity.current) { DRAG_SCROLL_BAND.toPx() }
    val stepPx = with(LocalDensity.current) { DRAG_SCROLL_STEP.toPx() }
    // A drag held near the top or bottom of the list scrolls it, so an item can reach a section that
    // started off screen. Per frame, unlike the page flip: a slide should not jump.
    LaunchedEffect(drag, page.id, dragging) {
        if (drag == null || !dragging) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val delta = DragScroll.delta(drag.pointer.y, viewport.top, viewport.bottom, bandPx, stepPx)
            val allowed = if (delta < 0f) listState.canScrollBackward else listState.canScrollForward
            if (delta != 0f && allowed) listState.scrollBy(delta)
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { viewport = it.boundsInRoot().toBox2() },
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            body.forEachIndexed { i, section ->
                item(key = section.id) { Box(Modifier.sectionEntry(i, entrySignal)) { SectionContent(section, layout, ctx, enabled) } }
            }
            item(key = "footer") { footer() }
        }
        aboveDock()
        docks.forEach { SectionContent(it, layout, ctx) }
    }
}

@Composable
private fun SectionContent(section: Section, layout: ResolvedLayout, ctx: RenderContext, enabled: Boolean = true) {
    // The page holding this section: widget drops and box registration are page-scoped, so every
    // section (grids and dock included) registers with it — a widget must order against them too.
    val pageId = layout.layout.pages.firstOrNull { p -> p.sections.any { it.id == section.id } }?.id
    when (section) {
        is HeaderSection -> Header(section, ctx)
        is GridSection -> ItemGrid(section.id, section.title, section.items, section.columns, ctx, pageId)
        is DockSection -> Dock(section, ctx, pageId)
        is ListSection -> ItemList(section.id, section.title, section.items, ctx, pageId)
        is GroupSection -> Group(section, ctx)
        // A resolved layout always carries the widget inline (refs are filled in from the library).
        is WidgetSection -> section.widget?.let {
            ctx.widgets?.let { source ->
                val menu = ctx.onWidgetMenu
                val draggable = menu != null && pageId != null && ctx.drag != null && ctx.onWidgetDrop != null && !ctx.locked && ctx.isBaseSection(section.id)
                // Draggable cards keep a no-op long-press so the press is consumed: otherwise the
                // background's long-press (Add widget sheet) races the drag's own hold-to-menu.
                // The real menu comes from sectionDrag at MENU_HOLD_MS; a move cancels it.
                // WidgetCard's own combinedClickable (onClick + the no-op) consumes the press.
                val cardSource = if (draggable) source.copy(onLongPress = {}) else if (menu == null) source else source.copy(onLongPress = { menu(it, section.id) })
                val cardModifier = if (draggable) {
                    Modifier.dropSection(section.id, ctx, pageId)
                        .sectionDrag(section.id, pageId!!, { menu!!(it, section.id) }, ctx)
                        .graphicsLayer { alpha = if (ctx.drag?.draggingSection == section.id) 0.3f else 1f }
                } else Modifier
                com.samosaai.masala.ui.home.widgets.WidgetCard(
                    it, layout.widgetRows[section.id], cardSource, cardModifier, enabled = enabled,
                )
            } ?: Placeholder(it.title ?: it.id, stringResource(R.string.home_widget_placeholder), layout.widgetRows[section.id])
        }
        is StackSection -> section.widgets.firstOrNull()?.let {
            ctx.widgets?.let { source ->
                val menu = ctx.onWidgetMenu
                val draggable = menu != null && pageId != null && ctx.drag != null && ctx.onWidgetDrop != null && !ctx.locked && ctx.isBaseSection(section.id)
                val cardSource = if (draggable) source.copy(onLongPress = {}) else if (menu == null) source else source.copy(onLongPress = { menu(it, section.id) })
                val cardModifier = if (draggable) {
                    Modifier.dropSection(section.id, ctx, pageId)
                        .sectionDrag(section.id, pageId!!, { menu!!(it, section.id) }, ctx)
                        .graphicsLayer { alpha = if (ctx.drag?.draggingSection == section.id) 0.3f else 1f }
                } else Modifier
                com.samosaai.masala.ui.home.widgets.StackCard(
                    section.widgets, layout.widgetRows[section.id], cardSource, cardModifier, enabled = enabled,
                )
            } ?: Placeholder(it.title ?: it.id, stringResource(R.string.home_stack_placeholder, section.widgets.size), layout.widgetRows[section.id])
        }
        is SkillWidgetSection -> ctx.skillWidgets?.let { SkillWidgetCard(section, it, ctx.onAction) }
            ?: Placeholder(section.skillId, stringResource(R.string.home_skill_widget_placeholder), null)
        is com.samosaai.masala.core.contract.CellGridSection -> CellGridView(section, ctx, enabled)
        // An HTML widget draws its mini app at widget size, sandboxed like its own screen.
        is com.samosaai.masala.core.contract.HtmlSection -> ctx.htmlWidget(section.appId)?.let { row ->
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = ((section.rows) * 48).dp),
            ) {
                com.samosaai.masala.miniapps.MiniAppView(row.id, row.html, row.networkAllowed, Modifier.fillMaxWidth())
            }
        } ?: Placeholder(section.appId, stringResource(R.string.home_widget_placeholder), section.rows)
    }
}

// ---- header ----

@Composable
private fun Header(section: HeaderSection, ctx: RenderContext) {
    val now = ctx.clock()
    val time = remember(now.hour, now.minute) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(now) }
    val date = remember(now.toLocalDate()) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(now) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        ctx.modeName?.let { ModeChip(it, ctx.onModeChip) }
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
        if (section.showDate) Text(date, style = wallpaperText(MaterialTheme.typography.bodyMedium), modifier = Modifier.padding(top = 6.dp))
    }
}

/** "Evening · auto": the mode that's shaping home, as a glass chip (home boards). */
@Composable
private fun ModeChip(name: String, onClick: (() -> Unit)?) {
    val tokens = MasalaTheme.tokens
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.padding(bottom = 18.dp).heightIn(min = 34.dp).clip(shape).background(tokens.wallpaperGlass)
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.WbTwilight, null, tint = tokens.accent, modifier = Modifier.size(18.dp))
        Text(name, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = OnWallpaper)
    }
}

// ---- items ----

/** A cell grid row: an icon cell's height, two 48 dp widget rows ([CellLift.ROWS_PER_CELL]). */
private val CELL_HEIGHT = (com.samosaai.masala.core.migration.CellLift.ROWS_PER_CELL * 48).dp

/**
 * A launcher grid (plan `docs/plans/home-cell-grid.md`): every cell at its column and row with its
 * span. Cards fill their cell; a card taller than its cell is clipped until it is resized.
 */
@Composable
private fun CellGridView(section: com.samosaai.masala.core.contract.CellGridSection, ctx: RenderContext, enabled: Boolean) {
    if (section.cells.isEmpty()) return
    val drag = ctx.drag
    if (drag != null) DisposableEffect(drag, section.id) { onDispose { drag.grids.remove(section.id) } }
    val cellHeightPx = with(LocalDensity.current) { CELL_HEIGHT.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val cellWidth = maxWidth / section.columns
        // A drag can push cells below the last row: room for one more row while dragging.
        val rows = section.rows + if (drag?.draggingCell != null && section.cells.any { it.id == drag.draggingCell?.id }) 1 else 0
        Box(
            Modifier.fillMaxWidth().height(CELL_HEIGHT * rows).onGloballyPositioned {
                if (drag == null) return@onGloballyPositioned
                val box = it.boundsInRoot()
                // Off screen (a page kept for the drag) the clipped box is empty: not a target.
                if (box.isEmpty) drag.grids.remove(section.id)
                else drag.grids[section.id] = GridGeom(box.toBox2(), section.columns, it.size.width.toFloat() / section.columns, cellHeightPx)
            },
        ) {
            for (cell in section.cells) {
                Box(
                    Modifier
                        .offset(x = cellWidth * cell.x.coerceAtLeast(0), y = CELL_HEIGHT * cell.y.coerceAtLeast(0))
                        .size(cellWidth * cell.w.coerceAtLeast(1), CELL_HEIGHT * cell.h.coerceAtLeast(1))
                        .padding(4.dp)
                        .graphicsLayer { alpha = if (drag?.draggingCell?.id == cell.id) 0.3f else 1f },
                ) { CellContentView(cell, section.id, ctx, enabled) }
                if (cell.id == ctx.resizingCell) ResizeHandle(cell, section.columns, cellWidth, ctx)
            }
        }
    }
}

@Composable
private fun CellContentView(cell: com.samosaai.masala.core.contract.Cell, gridId: String, ctx: RenderContext, enabled: Boolean) {
    val rows = cell.h * com.samosaai.masala.core.migration.CellLift.ROWS_PER_CELL
    val fill = Modifier.fillMaxSize()
    when (val content = cell.content) {
        // Not a drop target or draggable yet (deferred to step 5); taps and the item menu work as anywhere else.
        is com.samosaai.masala.core.contract.CellContent.ItemCell -> ItemCell(content.item, ctx, sectionId = null)
        is com.samosaai.masala.core.contract.CellContent.WidgetCell -> content.widget?.let { spec ->
            ctx.widgets?.let { com.samosaai.masala.ui.home.widgets.WidgetCard(spec, rows, cellSource(it, cell, spec, ctx), fill.cellDrag(cell, gridId, spec, ctx), enabled = enabled) }
                ?: Placeholder(spec.title ?: spec.id, stringResource(R.string.home_widget_placeholder), rows)
        }
        is com.samosaai.masala.core.contract.CellContent.StackCell -> if (content.widgets.isNotEmpty()) {
            ctx.widgets?.let { com.samosaai.masala.ui.home.widgets.StackCard(content.widgets, rows, cellSource(it, cell, content.widgets.first(), ctx), fill.cellDrag(cell, gridId, content.widgets.first(), ctx), enabled = enabled) }
                ?: Placeholder(content.widgets.first().title ?: content.widgets.first().id, stringResource(R.string.home_stack_placeholder, content.widgets.size), rows)
        }
        is com.samosaai.masala.core.contract.CellContent.SkillCell -> {
            val section = SkillWidgetSection(cell.id, content.skillId)
            ctx.skillWidgets?.let { SkillWidgetCard(section, it, ctx.onAction) }
                ?: Placeholder(content.skillId, stringResource(R.string.home_skill_widget_placeholder), rows)
        }
        is com.samosaai.masala.core.contract.CellContent.HtmlCell -> ctx.htmlWidget(content.appId)?.let { row ->
            com.samosaai.masala.miniapps.MiniAppView(row.id, row.html, row.networkAllowed, fill)
        } ?: Placeholder(content.appId, stringResource(R.string.home_widget_placeholder), rows)
    }
}

/**
 * The resize frame on one cell: an outline at the size the handle shows and a round handle at its
 * bottom-right. Dragging snaps to whole cells (inside the columns, at least 1 × 1); letting go saves.
 */
@Composable
private fun ResizeHandle(cell: com.samosaai.masala.core.contract.Cell, columns: Int, cellWidth: androidx.compose.ui.unit.Dp, ctx: RenderContext) {
    val density = LocalDensity.current
    val cellWidthPx = with(density) { cellWidth.toPx() }
    val cellHeightPx = with(density) { CELL_HEIGHT.toPx() }
    // Within the widget's own width range and the columns right of it; never an empty range.
    val spec = when (val c = cell.content) {
        is com.samosaai.masala.core.contract.CellContent.WidgetCell -> c.widget
        is com.samosaai.masala.core.contract.CellContent.StackCell -> c.widgets.firstOrNull()
        else -> null
    }
    val maxW = minOf(spec?.size?.maxW ?: columns, columns - cell.x).coerceAtLeast(1)
    val minW = (spec?.size?.minW ?: 1).coerceIn(1, maxW)
    // Height up to the widget's tallest and the grid's last row; a cell already taller can keep its height.
    val maxH = minOf(
        spec?.size?.max?.let { com.samosaai.masala.core.layout.LayoutEdits.cellsHigh(it) } ?: Int.MAX_VALUE,
        com.samosaai.masala.core.layout.LayoutChecker.MAX_GRID_ROWS - cell.y,
    ).coerceAtLeast(cell.h)
    var w by remember(cell) { mutableStateOf(cell.w) }
    var h by remember(cell) { mutableStateOf(cell.h) }
    var delta by remember(cell) { mutableStateOf(Offset.Zero) }
    val accent = MasalaTheme.tokens.accent
    Box(
        Modifier
            .offset(x = cellWidth * cell.x, y = CELL_HEIGHT * cell.y)
            .size(cellWidth * w, CELL_HEIGHT * h)
            .padding(2.dp)
            .border(2.dp, accent, RoundedCornerShape(28.dp)),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(36.dp)
                .background(accent, androidx.compose.foundation.shape.CircleShape)
                // A tap without a drag closes the frame unchanged.
                .pointerInput(cell.id, "tap") {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val up = waitForUpOrCancellation()
                        if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                            ctx.onCellResize?.invoke(cell.id, cell.w, cell.h)
                        }
                    }
                }
                .pointerInput(cell.id) {
                    detectDragGestures(
                        onDragEnd = { ctx.onCellResize?.invoke(cell.id, w, h); delta = Offset.Zero },
                        onDragCancel = { w = cell.w; h = cell.h; delta = Offset.Zero },
                    ) { change, amount ->
                        change.consume()
                        delta += amount
                        w = (cell.w + kotlin.math.round(delta.x / cellWidthPx).toInt()).coerceIn(minW, maxW)
                        h = (cell.h + kotlin.math.round(delta.y / cellHeightPx).toInt()).coerceIn(1, maxH)
                    }
                },
        )
    }
}

/** A widget cell's long-press opens the widget menu for that placement (by cell id); never while locked. */
private fun cellSource(
    source: com.samosaai.masala.ui.home.widgets.WidgetSource,
    cell: com.samosaai.masala.core.contract.Cell,
    spec: com.samosaai.masala.core.contract.WidgetSpec,
    ctx: RenderContext,
): com.samosaai.masala.ui.home.widgets.WidgetSource {
    val menu = ctx.onWidgetMenu?.takeIf { !ctx.locked && ctx.isBaseSection(cell.id) } ?: return source
    // A draggable card keeps a no-op long-press so the press is consumed; cellDrag opens the menu.
    if (cellDraggable(cell, ctx)) return source.copy(onLongPress = {})
    return source.copy(onLongPress = { menu(spec, cell.id) })
}

private fun cellDraggable(cell: com.samosaai.masala.core.contract.Cell, ctx: RenderContext) =
    ctx.drag != null && ctx.onCellDrop != null && ctx.onWidgetMenu != null && !ctx.locked && ctx.isBaseSection(cell.id)

/**
 * Long-press-then-drag a widget cell to another spot on its grid, snapping to cells; a still finger
 * opens the widget menu after [MENU_HOLD_MS]. Same gesture shape as [sectionDrag].
 */
@Composable
private fun Modifier.cellDrag(
    cell: com.samosaai.masala.core.contract.Cell,
    gridId: String,
    spec: com.samosaai.masala.core.contract.WidgetSpec,
    ctx: RenderContext,
): Modifier {
    if (!cellDraggable(cell, ctx)) return this
    val drag = ctx.drag!!
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val menu = { ctx.onWidgetMenu?.invoke(spec, cell.id); Unit }
    var coords by remember(cell.id) { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val latest by androidx.compose.runtime.rememberUpdatedState(cell)
    return this
        .onGloballyPositioned { coords = it }
        .pointerInput(cell.id, gridId, drag) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                haptics.heavy()
                long.consume()
                fun rootOf(p: Offset) = coords?.takeIf { it.isAttached }?.localToRoot(p) ?: p
                var moved = false
                var opened = false
                val openMenu = scope.launch {
                    delay((down.uptimeMillis + MENU_HOLD_MS - long.uptimeMillis).coerceAtLeast(0L))
                    if (!moved) {
                        opened = true
                        menu()
                    }
                }
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (!moved && !opened && (change.position - long.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            openMenu.cancel()
                            drag.startCell(latest, gridId, rootOf(change.position))
                        }
                        if (moved) drag.moveTo(rootOf(change.position))
                        change.consume()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (moved && drag.draggingCell?.id == cell.id) drag.cancelCell()
                    throw e
                } finally {
                    openMenu.cancel()
                }
                when {
                    moved -> drag.dropCell()?.let { haptics.confirm(); ctx.onCellDrop?.invoke(cell.id, it) }
                    !opened -> menu()
                }
            }
        }
}

/** Columns that fit: the section's own number, fewer when the screen is narrow or the text is large. */
@Composable
private fun columnsFor(wanted: Int, maxWidthDp: Float): Int {
    val scale = LocalDensity.current.fontScale
    val cell = 76f * scale.coerceAtLeast(1f)
    return minOf(wanted.coerceAtLeast(1), (maxWidthDp / cell).toInt().coerceAtLeast(1))
}

@Composable
private fun ItemGrid(sectionId: String, title: String?, items: List<Item>, columns: Int, ctx: RenderContext, pageId: String? = null) {
    if (items.isEmpty() && title.isNullOrBlank()) return
    Column(Modifier.fillMaxWidth().dropSection(sectionId, ctx, pageId).padding(horizontal = 12.dp)) {
        if (!title.isNullOrBlank()) Text(title, style = wallpaperText(MaterialTheme.typography.titleSmall), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = columnsFor(columns, maxWidth.value)
            Column {
                items.chunked(cols).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { item -> Box(Modifier.weight(1f)) { ItemCell(item, ctx, sectionId) } }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Dock(section: DockSection, ctx: RenderContext, pageId: String? = null) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 78.dp).dropSection(section.id, ctx, pageId).clip(shape)
            .background(MasalaTheme.tokens.wallpaperGlass).border(1.dp, Color.White.copy(alpha = 0.09f), shape).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        section.items.forEach { item -> Box(Modifier.weight(1f)) { ItemCell(item, ctx, section.id, showLabel = false) } }
    }
}

@Composable
private fun ItemList(sectionId: String, title: String?, items: List<Item>, ctx: RenderContext, pageId: String? = null) {
    Column(Modifier.fillMaxWidth().dropSection(sectionId, ctx, pageId)) {
        if (!title.isNullOrBlank()) Text(title, style = wallpaperText(MaterialTheme.typography.titleSmall), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        items.forEach { item -> ItemRow(item, ctx, sectionId) }
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
        if (open) ItemGrid(section.id, null, section.items, 4, ctx)
    }
}

@Composable
internal fun ItemCell(
    item: Item,
    ctx: RenderContext,
    sectionId: String?,
    showLabel: Boolean = true,
    interactive: Boolean = true,
    /** Off on a sheet, where the label takes the surface's colour instead of white with a shadow. */
    onWallpaper: Boolean = true,
) {
    val app = appFor(item, ctx)
    val label = app?.takeIf { it.renamed }?.label ?: item.label
    val spoken = spokenLabel(item, label)
    val bounds = remember { IconBounds() }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (interactive) Modifier.dropItem(item, sectionId, ctx, bounds).itemClicks(item, app, ctx, spoken, bounds) else Modifier)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ItemIcon(item, app, ctx, size = 48)
        if (showLabel) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.let { if (onWallpaper) wallpaperText(it) else it },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

@Composable
private fun ItemRow(item: Item, ctx: RenderContext, sectionId: String) {
    val app = appFor(item, ctx)
    val label = app?.takeIf { it.renamed }?.label ?: item.label
    val bounds = remember { IconBounds() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).dropItem(item, sectionId, ctx, bounds).itemClicks(item, app, ctx, spokenLabel(item, label), bounds)
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemIcon(item, app, ctx, size = 40)
        Text(label, style = wallpaperText(MaterialTheme.typography.titleMedium), modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * Records where the item sits (for opening apps from it and for drops), and fades it while it is the one
 * being dragged. Its drop box goes when it leaves, so pages scrolled away leave no stale boxes.
 */
@Composable
private fun Modifier.dropItem(item: Item, sectionId: String?, ctx: RenderContext, bounds: IconBounds): Modifier {
    val drag = ctx.drag
    if (drag != null && sectionId != null) {
        DisposableEffect(drag, item.id) { onDispose { drag.itemBoxes.remove(item.id) } }
    }
    return this
        .onGloballyPositioned {
            bounds.set(it)
            if (drag != null && sectionId != null) {
                // Off screen (a page kept for the drag) the clipped box is empty: not a target.
                val box = it.boundsInRoot()
                if (box.isEmpty) drag.itemBoxes.remove(item.id) else drag.itemBoxes[item.id] = sectionId to box.toBox2()
            }
        }
        .graphicsLayer { alpha = if (drag?.dragging?.id == item.id) 0.3f else 1f }
}

/** Registers an item section's box as a drop area while it is on screen. */
@Composable
private fun Modifier.dropSection(sectionId: String, ctx: RenderContext, pageId: String? = null): Modifier {
    val drag = ctx.drag ?: return this
    DisposableEffect(drag, sectionId) {
        onDispose {
            drag.sectionBoxes.remove(sectionId)
            drag.sectionPages.remove(sectionId)
        }
    }
    return onGloballyPositioned {
        val box = it.boundsInRoot()
        if (box.isEmpty) {
            drag.sectionBoxes.remove(sectionId)
            drag.sectionPages.remove(sectionId)
        } else {
            drag.sectionBoxes[sectionId] = box.toBox2()
            if (pageId != null) drag.sectionPages[sectionId] = pageId
        }
    }
}

internal fun androidx.compose.ui.geometry.Rect.toBox2() = Box2(left, top, right, bottom)

/**
 * Long-press-then-drag to reorder a widget section on its page. Same shape as [itemClicks]: a
 * still finger opens the menu after [MENU_HOLD_MS]; moving past touch slop starts the drag and
 * cancels the menu. Consumes moves only after the long press, so taps, buttons and scrolling
 * behave as before. Cross-page moves stay in the menu.
 */
@Composable
private fun Modifier.sectionDrag(sectionId: String, pageId: String, menu: () -> Unit, ctx: RenderContext): Modifier {
    val drag = ctx.drag?.takeIf { ctx.onWidgetDrop != null } ?: return this
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var coords by remember(sectionId) { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    return this
        .onGloballyPositioned { coords = it }
        .pointerInput(sectionId, pageId, drag) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                haptics.heavy()
                long.consume()
                fun rootOf(p: Offset) = coords?.takeIf { it.isAttached }?.localToRoot(p) ?: p
                var moved = false
                var opened = false
                val openMenu = scope.launch {
                    delay((down.uptimeMillis + MENU_HOLD_MS - long.uptimeMillis).coerceAtLeast(0L))
                    if (!moved) {
                        opened = true
                        menu()
                    }
                }
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (!moved && !opened && (change.position - long.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            openMenu.cancel()
                            drag.startSection(sectionId, pageId, rootOf(change.position))
                        }
                        if (moved) drag.moveTo(rootOf(change.position))
                        change.consume()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (moved && drag.draggingSection == sectionId) drag.cancelSection()
                    throw e
                } finally {
                    openMenu.cancel()
                }
                when {
                    moved -> drag.dropSection()?.let { haptics.confirm(); ctx.onWidgetDrop?.invoke(sectionId, it) }
                    !opened -> menu()
                }
            }
        }
}

/**
 * Tap runs the action. A long press opens the item's menu unless the home layout is locked; on an item
 * of the base layout, moving past touch slop after the long press drags it instead (no menu then).
 * After the long press every move and the lift are consumed early, so neither the tap nor the pager,
 * the page's list or home's swipes see them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.itemClicks(item: Item, app: AppEntry?, ctx: RenderContext, label: String, bounds: IconBounds? = null): Modifier {
    val menuLabel = stringResource(R.string.menu_options)
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val drag = ctx.drag?.takeIf { item.id in ctx.movable && !ctx.locked }
    val menu: (() -> Unit)? = when {
        ctx.locked -> null
        item.action is MasalaAction.OpenFolder -> ctx.onFolderMenu?.let { onMenu -> { onMenu(item) } }
        app != null -> ({ ctx.onAppMenu(app) })
        else -> ctx.onItemMenu?.let { onMenu -> { onMenu(item) } }
    }
    val dragging = if (drag == null) Modifier else Modifier.pointerInput(item.id, drag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            haptics.heavy()
            long.consume()
            fun rootOf(p: Offset) = bounds?.coordinates?.takeIf { it.isAttached }?.localToRoot(p) ?: p
            var moved = false
            var opened = false
            // Keep holding a still finger and the menu opens by itself, without waiting for the lift;
            // moving instead starts the drag and cancels this.
            // Counted from the touch down, so the menu lands at [MENU_HOLD_MS] whether the long press
            // fired late or early; the press itself already used part of it.
            val openMenu = scope.launch {
                delay((down.uptimeMillis + MENU_HOLD_MS - long.uptimeMillis).coerceAtLeast(0L))
                if (!moved) {
                    opened = true
                    menu?.invoke()
                }
            }
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    // After the menu is up the gesture is over: no drag should start under it.
                    if (!moved && !opened && (change.position - long.position).getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                        openMenu.cancel()
                        drag.start(item, rootOf(change.position))
                    }
                    if (moved) drag.moveTo(rootOf(change.position))
                    change.consume()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The cell can leave mid-drag (its page or section goes away): never leave the copy lifted.
                if (moved && drag.dragging?.id == item.id) drag.cancel()
                throw e
            } finally {
                openMenu.cancel()
            }
            when {
                moved -> drag.drop()?.let { haptics.confirm(); ctx.onDrop?.invoke(item, it) }
                // A quick lift still opens it; a hold has already done so.
                !opened -> menu?.invoke()
            }
        }
    }
    return this
        .then(dragging)
        .combinedClickable(
            onClick = {
                val from = bounds?.rect
                if (item.action is MasalaAction.OpenFolder) ctx.onOpenFolder?.invoke(item)
                else if (from != null && item.action is MasalaAction.LaunchApp && ctx.onLaunchFrom != null) ctx.onLaunchFrom.invoke(item.action, from) else ctx.onAction(item.action)
            },
            onLongClick = if (drag == null) menu else null,
            onLongClickLabel = if (drag == null && menu != null) menuLabel else null,
        )
        .semantics {
            contentDescription = label
            role = Role.Button
            // The drag gesture replaces the long click, so TalkBack still needs a way to the menu.
            if (drag != null && menu != null) onLongClick(menuLabel) { menu(); true }
        }
}

internal fun appFor(item: Item, ctx: RenderContext): AppEntry? {
    val a = item.action as? MasalaAction.LaunchApp ?: return null
    return ctx.appFor(a.packageName, a.user, a.activity)
}

/** A folder's 2×2 preview, a mini app's stored icon, or the item's own icon. */
@Composable
internal fun ItemIcon(item: Item, app: AppEntry?, ctx: RenderContext, size: Int) {
    val folder = item.action as? MasalaAction.OpenFolder
    if (folder != null) {
        FolderIcon(folder.items, ctx, size)
        return
    }
    val miniApp = item.action as? MasalaAction.OpenMiniApp
    val stored = miniApp?.let { ctx.miniAppIcon(it.id) }
    if (miniApp != null && stored != null) {
        MiniAppIconView(stored, item.label, size.dp)
        return
    }
    // The icon the user chose for an app follows the app everywhere: a layout that drew a symbol for
    // the slot doesn't win over an explicit choice.
    val chosen = app?.let { ctx.icons.override(it.key) }
    if (chosen != null) {
        SpecIcon(chosen, app?.label.orEmpty(), size.dp)
        return
    }
    IconView(item.icon, app, ctx, size)
}

/** What TalkBack reads: a folder says it is one and how many apps it holds. */
@Composable
private fun spokenLabel(item: Item, label: String): String {
    val folder = item.action as? MasalaAction.OpenFolder ?: return label
    return pluralStringResource(R.plurals.folder_description, folder.items.size, label, folder.items.size)
}

@Composable
internal fun IconView(icon: IconRef, app: AppEntry?, ctx: RenderContext, size: Int) {
    when (icon) {
        is IconRef.AppIcon -> {
            // The same app in another profile has its own key and badge.
            val entry = app ?: ctx.appFor(icon.packageName, icon.user, null)
            val chosen = entry?.let { ctx.icons.override(it.key) }
            val bitmap = entry?.takeIf { chosen == null }?.let { rememberAppIcon(ctx.icons, it.key) }
            when {
                chosen != null -> SpecIcon(chosen, entry?.label.orEmpty(), size.dp)
                bitmap != null -> Image(bitmap, contentDescription = null, modifier = Modifier.size(size.dp))
                else -> SymbolDisc(Icons.Rounded.Apps, null, size)
            }
        }
        is IconRef.Symbol -> SymbolDisc(SymbolIcons.icon(icon.name), SymbolIcons.tint(icon.tint), size)
        // An id that names an installed app draws that app (with whatever icon it carries); anything
        // else is the generic app disc, never a blank circle.
        is IconRef.Override -> {
            val key = runCatching { com.samosaai.masala.core.apps.AppKey.parse(icon.id) }.getOrNull()
            val entry = key?.let { ctx.appFor(it.packageName, it.user, null) }
            val chosen = entry?.let { ctx.icons.override(it.key) }
            val bitmap = entry?.takeIf { chosen == null }?.let { rememberAppIcon(ctx.icons, it.key) }
            when {
                chosen != null -> SpecIcon(chosen, entry?.label.orEmpty(), size.dp)
                bitmap != null -> Image(bitmap, contentDescription = null, modifier = Modifier.size(size.dp))
                else -> SymbolDisc(Icons.Rounded.Apps, null, size)
            }
        }
    }
}

/** A symbol in the same mask as the app icons (plan UI refresh U1: vectors, not emoji on discs). */
@Composable
internal fun SymbolDisc(icon: ImageVector, tint: Color?, size: Int) {
    val shape = LocalContext.current.masala.icons.style.shape
    IconTile(icon, Modifier.clearAndSetSemantics { }, size = size.dp, tint = tint, shape = shape)
}

@Composable
internal fun Placeholder(title: String, note: String, rows: Int?) {
    WallpaperCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = ((rows ?: 2) * 48).dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = OnWallpaper)
        Spacer(Modifier.height(4.dp))
        Text(note, style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium)
    }
}

/** A glass card straight on the wallpaper (home boards): 26 dp corners, stronger glass, white hairline. */
@Composable
internal fun WallpaperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier.clip(shape).background(MasalaTheme.tokens.wallpaperGlass).border(1.dp, Color.White.copy(alpha = 0.10f), shape).padding(18.dp),
        content = content,
    )
}

/** Where an item sits in the window, so an app opened from it grows out of its icon. Not state: no recomposition. */
internal class IconBounds {
    var rect: android.graphics.Rect? = null
        private set

    /** The item's layout, to turn a touch on it into root coordinates while dragging. */
    var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
        private set

    fun set(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
        this.coordinates = coordinates
        val b = coordinates.boundsInWindow()
        rect = android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
    }
}

/** Total touch time, long press included, after which a still finger opens an item's menu. */
private const val MENU_HOLD_MS = 1_000L

/** Holding a dragged item this close to a side for [EDGE_HOLD_MS] flips to the next page that way. */
private val EDGE_FLIP_ZONE = 24.dp
/** How close to the list's top or bottom a drag scrolls it, and how far per frame at the deepest. */
private val DRAG_SCROLL_BAND = 72.dp
private val DRAG_SCROLL_STEP = 18.dp
private const val EDGE_HOLD_MS = 600L
private const val EDGE_TICK_MS = 100L

/** Gap between sections arriving after an AI layout is applied (motion spec: 40–120 ms apart). */
private const val SECTION_STAGGER_MS = 80L

/**
 * A new layout assembles in place (building board): each section fades and rises in turn after
 * [signal] changes; with reduced motion, a plain fade.
 */
@Composable
private fun Modifier.sectionEntry(index: Int, signal: Int): Modifier {
    val reduced = com.samosaai.masala.ui.theme.LocalMotion.current.reduced
    val shown = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        shown.snapTo(0f)
        kotlinx.coroutines.delay(minOf(index * SECTION_STAGGER_MS, 600L))
        shown.animateTo(1f, androidx.compose.animation.core.tween(com.samosaai.masala.ui.theme.Motion.SHEET_MS))
    }
    return graphicsLayer {
        alpha = shown.value
        translationY = if (reduced) 0f else (1f - shown.value) * 24.dp.toPx()
    }
}
