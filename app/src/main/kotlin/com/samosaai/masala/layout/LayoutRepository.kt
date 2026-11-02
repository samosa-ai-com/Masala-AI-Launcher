package com.samosaai.masala.layout

import android.content.Context
import android.util.Log
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.core.layout.LayoutEdits
import com.samosaai.masala.core.layout.LayoutHistory
import com.samosaai.masala.core.layout.ResolveCache
import com.samosaai.masala.core.layout.ResolvedLayout
import com.samosaai.masala.core.migration.CellLift
import com.samosaai.masala.core.migration.LayoutMigrations
import com.samosaai.masala.core.migration.WidgetLift
import com.samosaai.masala.widgets.WidgetLibrary
import com.samosaai.masala.context.ContextService
import com.samosaai.masala.data.LayoutVersionDao
import com.samosaai.masala.data.LayoutVersionEntity
import com.samosaai.masala.launcher.AppCatalog
import com.samosaai.masala.launcher.LauncherModel
import com.samosaai.masala.skills.SkillRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The home layout (plan Phase 2): the base layout in memory as a flow, saved to Room in the background
 * with 50 labeled versions for undo and redo, resolved against modes and context for the renderer.
 *
 * The first frame comes from a small file with the last resolved layout, read before Room is touched
 * (Lawnchair §5 #1), so a cold start draws home at once; Room and the app catalog catch up behind it.
 * [firstFrame] says where it came from, for the cold-start check.
 */
class LayoutRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: LayoutVersionDao,
    private val catalog: AppCatalog,
    private val launcher: LauncherModel,
    private val modes: ModeStore,
    private val contextService: ContextService,
    private val skills: () -> SkillRegistry,
    private val toolNames: () -> Set<String>,
    private val widgets: WidgetLibrary,
    private val streams: com.samosaai.masala.widgets.StreamSourceStore,
    private val miniApps: com.samosaai.masala.miniapps.MiniAppStore,
) {
    enum class Source { CACHE, MINIMAL }

    @Serializable
    private data class Cached(val layout: LayoutDoc, val widgetRows: Map<String, Int>)

    private data class Base(val version: Long, val layout: LayoutDoc)

    private data class Inputs(
        val base: Base,
        val patches: List<com.samosaai.masala.core.contract.ModePatch>,
        val snapshot: com.samosaai.masala.core.contract.ContextSnapshot,
    )

    private val cacheFile = File(context.filesDir, "resolved_layout.json")
    private val pointer = context.getSharedPreferences("layout", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var history = LayoutHistory()
    private val resolveCache = ResolveCache()

    private val _base = MutableStateFlow<Base?>(null)
    private val _state = MutableStateFlow(HistoryState())

    /** What the undo and redo buttons and the version list show. */
    data class HistoryState(val canUndo: Boolean = false, val canRedo: Boolean = false, val versions: List<Version> = emptyList())
    data class Version(val id: Long, val label: String, val createdAt: Long, val current: Boolean)

    val historyState: StateFlow<HistoryState> = _state.asStateFlow()

    /** The base layout, once loaded. */
    val base: StateFlow<LayoutDoc?> = MutableStateFlow<LayoutDoc?>(null).also { out ->
        scope.launch { _base.filterNotNull().collect { out.value = it.layout } }
    }.asStateFlow()

    val firstFrame: Source
    private val _resolved: MutableStateFlow<ResolvedLayout>

    /** What the renderer draws. */
    val resolved: StateFlow<ResolvedLayout>

    init {
        val cached = readCache()
        firstFrame = if (cached != null) Source.CACHE else Source.MINIMAL
        _resolved = MutableStateFlow(cached ?: ResolvedLayout(ClassicLayoutBuilder.minimal, emptyMap(), emptyList()))
        resolved = _resolved.asStateFlow()
        Log.i(TAG, "First frame from $firstFrame")

        scope.launch { load() }
        scope.launch {
            combine(_base.filterNotNull(), modes.patches, modes.active, contextService.snapshot) { base, patches, active, snapshot ->
                Inputs(base, patches.filter { it.id in active }, snapshot)
            }.let { inputs ->
                combine(inputs, widgets.widgets, widgets.version) { i, lib, libVersion ->
                    resolveCache.resolve(i.base.version, i.base.layout, i.patches, i.snapshot, libVersion, lib)
                }
            }.flowOn(Dispatchers.Default).collect { r ->
                _resolved.value = r
                writeCache(r)
            }
        }
    }

    // ---- loading ----

    private suspend fun load() {
        val saved = dao.all().mapNotNull { row ->
            runCatching {
                LayoutHistory.Version(row.id, row.label, LayoutMigrations.decode(MasalaJson.strict.parseToJsonElement(row.json)), row.createdAt)
            }.onFailure { Log.w(TAG, "Skipping unreadable layout version ${row.id}", it) }.getOrNull()
        }
        // Templates seed only when nothing was ever saved: an emptied library stays empty.
        widgets.load(seedIfEmpty = saved.isEmpty())
        streams.load()
        history = LayoutHistory(saved, pointer.getLong(KEY_CURRENT, -1).takeIf { it >= 0 })
        if (history.current == null) {
            // Fresh install, or nothing readable: Classic, once the app list is known.
            catalog.state.first { it.loaded }
            val classic = ClassicLayoutBuilder.build(context, launcher.apps.value, launcher.pinnedApps.value)
            lock.withLock { push("Classic", classic) }
        }
        liftInlineWidgets()
        foldIntoCells()
        // Heal layouts bricked by the old delete order (library deleted before refs stripped):
        // an undo or a crash could leave refs to missing widgets that reject every later edit.
        history.current?.let { repairDanglingRefs(it) }
        publish()
    }

    /**
     * Schema 3 once: every inline widget still saved in the layout moves into the library and the
     * layout keeps a ref, as one undoable version (plan `docs/plans/widget-maker.md` §6.1). Mode
     * patches lift too — later loads find no inline widgets anywhere and do nothing. The push
     * happens whenever the layout still differs (even with nothing new to save), so a crash
     * between the library save and the push resumes into the same refs instead of stranding
     * the layout inline for good.
     */
    private suspend fun liftInlineWidgets() {
        val current = history.current ?: return
        val patches = modes.patches.value
        val baseInline = WidgetLift.hasInline(current.layout)
        val patchInline = patches.any(WidgetLift::hasInline)
        if (!baseInline && !patchInline) return
        var known = widgets.current()
        val liftedBase = if (baseInline) WidgetLift.lift(current.layout, known) else null
        liftedBase?.let { known = known + it.widgets.associateBy { w -> w.id } }
        val liftedPatches = patches.mapNotNull { patch ->
            if (!WidgetLift.hasInline(patch)) null
            else patch to WidgetLift.lift(patch, known).also { known += it.widgets.associateBy { w -> w.id } }
        }
        val all = liftedBase?.widgets.orEmpty() + liftedPatches.flatMap { (_, lifted) -> lifted.widgets }
        if (all.isNotEmpty()) widgets.saveAll(all)
        liftedPatches.forEach { (patch, lifted) ->
            if (lifted.value != patch) modes.put(lifted.value)
        }
        val lifted = liftedBase?.value
        if (lifted != null && lifted != current.layout) lock.withLock { push("Widgets to library", lifted) }
    }

    /**
     * Schema 4 once per install: the home's widget sections become one cell grid each run, looking
     * as before (plan `docs/plans/home-cell-grid.md`), as one undoable version. Once only, so an undo
     * back past it stays undone; later placements land in grids anyway.
     */
    private suspend fun foldIntoCells() {
        if (pointer.getBoolean(KEY_CELLS_FOLDED, false)) return
        val current = history.current ?: return
        val folded = CellLift.fold(current.layout, widgets.current())
        if (folded != null) lock.withLock { push("Widgets to grid", folded) }
        pointer.edit().putBoolean(KEY_CELLS_FOLDED, true).commit()
    }

    private suspend fun push(label: String, layout: LayoutDoc) {
        val change = history.push(label, layout, System.currentTimeMillis())
        withContext(Dispatchers.IO) {
            if (change.removedIds.isNotEmpty()) dao.delete(change.removedIds)
            change.added?.let { dao.upsert(it.toEntity()) }
            pointer.edit().putLong(KEY_CURRENT, history.current!!.id).commit()
        }
    }

    private fun LayoutHistory.Version.toEntity() = LayoutVersionEntity(
        id = id,
        label = label,
        schemaVersion = layout.schemaVersion,
        json = MasalaJson.strict.encodeToString(LayoutDoc.serializer(), layout),
        createdAt = createdAt,
    )

    private fun publish() {
        val current = history.current
        _base.value = current?.let { Base(it.id, it.layout) }
        _state.value = HistoryState(
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            versions = history.versions.map { Version(it.id, it.label, it.createdAt, it.id == current?.id) }.asReversed(),
        )
    }

    private fun savePointer() = pointer.edit().putLong(KEY_CURRENT, history.current?.id ?: -1).commit()

    // ---- editing ----

    /**
     * Checks [layout] against what is installed and, if it passes, makes it the current layout under
     * [label]. Returns the problems when it doesn't; nothing is changed then.
     */
    suspend fun apply(label: String, layout: LayoutDoc): List<LayoutChecker.Problem> = lock.withLock {
        val pruned = LayoutEdits.pruneEmptyPages(layout, modePageIds()) ?: layout
        val problems = LayoutChecker.check(pruned, environment())
        if (problems.isEmpty()) {
            push(label, pruned)
            publish()
        }
        problems
    }

    /**
     * Applies [change] to the current base layout and saves the result under [label] if it passes the
     * checker. Returns the problems (empty when saved); a null change saves nothing. Reads the history
     * directly, not [base], which follows it a moment later. Must not call [apply]: [lock] isn't reentrant.
     */
    suspend fun edit(label: String, change: (LayoutDoc) -> LayoutDoc?): List<LayoutChecker.Problem> = lock.withLock {
        val current = history.current?.layout ?: return@withLock emptyList()
        val changed = change(current) ?: return@withLock emptyList()
        val next = LayoutEdits.pruneEmptyPages(changed, modePageIds()) ?: changed
        if (next == current) return@withLock emptyList()
        val problems = LayoutChecker.check(next, environment())
        if (problems.isEmpty()) {
            push(label, next)
            publish()
        }
        problems
    }

    /** Takes every home item [match] selects off the layout, wherever it sits, in one undoable step. */
    suspend fun removeItems(label: String, match: (Item) -> Boolean): List<LayoutChecker.Problem> =
        edit(label) { LayoutEdits.removeWhere(it, match = match) }

    /** Renames every item [match] selects, so a mini app's pin and its app share one name. */
    suspend fun renameItems(label: String, match: (Item) -> Boolean): List<LayoutChecker.Problem> =
        edit(label) { LayoutEdits.renameWhere(it, label, match) }

    /** Page ids mode patches add sections to: pruning never drops these. */
    private fun modePageIds(): Set<String> = modes.patches.value.flatMap { patch -> patch.addSections.map { it.pageId } }.toSet()

    /** Where one library widget sits on home, for the Widgets manager. */
    fun widgetPlacements(widgetId: String): List<LayoutEdits.WidgetPlacement> =
        base.value?.let { LayoutEdits.widgetPlacements(it, widgetId) }.orEmpty()

    /**
     * Puts a library widget on home: the requested page when it has room, else the first page
     * that does, else a new home screen. Always asks (arch §3.3), so the caller gates it.
     * One widget sits on home once: placing again reports where it already is.
     * Returns the check problems plus where it landed (or already sits), for the "on Home 2" message.
     */
    suspend fun placeWidget(
        label: String,
        widgetId: String,
        rowsOverride: Int? = null,
        pageId: String? = null,
    ): PlaceResult {
        widgetPlacements(widgetId).lastOrNull()?.let { return PlaceResult(emptyList(), it.pageId, alreadyPlaced = true) }
        val specs = widgets.current()
        val problems = edit(label) { LayoutEdits.placeWidget(it, widgetId, rowsOverride, pageId, specs)?.doc }
        if (problems.isNotEmpty()) return PlaceResult(problems, null)
        val at = widgetPlacements(widgetId).lastOrNull()?.pageId
        return PlaceResult(emptyList(), at)
    }

    data class PlaceResult(val problems: List<LayoutChecker.Problem>, val pageId: String?, val alreadyPlaced: Boolean = false)

    /** Takes one library widget off home wherever it sits, in one undoable step. */
    suspend fun removeWidgetRefs(label: String, widgetId: String): List<LayoutChecker.Problem> {
        val problems = edit(label) { LayoutEdits.removeWidgetRefs(it, widgetId) }
        if (problems.isNotEmpty()) return problems
        // Mode patches hold refs too; the layout checker never sees them, so strip them here.
        modes.patches.value.forEach { patch ->
            WidgetLift.stripRefs(patch, widgetId)?.let { modes.put(it) }
        }
        return emptyList()
    }

    /** Sets the per-placement height override on every ref of [widgetId]. */
    /** Sets one cell's width in cells, keeping its height; cells in the way go down. */
    suspend fun resizeCellWidth(label: String, cellId: String, width: Int): List<LayoutChecker.Problem> =
        edit(label) { doc ->
            val cell = doc.pages.flatMap { it.sections }.filterIsInstance<com.samosaai.masala.core.contract.CellGridSection>()
                .flatMap { it.cells }.firstOrNull { it.id == cellId }
            cell?.let { LayoutEdits.resizeCell(doc, cellId, width, it.h) }
        }

    suspend fun resizeWidget(label: String, widgetId: String, rows: Int): List<LayoutChecker.Problem> =
        edit(label) { LayoutEdits.resizeWidget(it, widgetId, rows) }

    /**
     * Moves a widget placement up/down ([steps]) or to another page: one undoable version,
     * same height cap as placing (the moved section's rendered height, stacks included). Returns
     * the check problems (empty means moved); a no-op reports where it already sits. When
     * [sectionId] names the exact placement (the menu passes the tapped one), that placement
     * moves instead of the first — placements on other pages stay put.
     */
    suspend fun moveWidget(
        label: String,
        widgetId: String,
        steps: Int = 0,
        targetPageId: String? = null,
        sectionId: String? = null,
    ): List<LayoutChecker.Problem> {
        val doc = base.value
            ?: return listOf(LayoutChecker.Problem("$", "Widget '$widgetId' is not on home"))
        val at = if (sectionId != null) {
            doc?.pages?.firstOrNull { p -> p.sections.any { it.id == sectionId } }
                ?.let { LayoutEdits.WidgetPlacement(it.id, sectionId) }
        } else {
            doc?.let { LayoutEdits.widgetPlacements(it, widgetId).firstOrNull() }
        } ?: return listOf(LayoutChecker.Problem("$", "Widget '$widgetId' is not on home"))
        // A cell (on a grid) moves by rows or onto the other page's grid.
        if (LayoutEdits.isCell(doc, at.sectionId)) {
            if (targetPageId == null && LayoutEdits.moveCellBy(doc, at.sectionId, steps) == null) {
                return listOf(LayoutChecker.Problem("$", "Widget '$widgetId' is already at the ${if (steps < 0) "top" else "bottom"}"))
            }
            return edit(label) { LayoutEdits.moveCellBy(it, at.sectionId, steps, targetPageId) }
        }
        val section = doc.pages.flatMap { it.sections }.firstOrNull { it.id == at.sectionId }
        if (targetPageId == null) {
            // The dock never moves: measure bounds in the movable sections only, so stopping
            // against it reports "already at the bottom" instead of a false Moved.
            val page = doc.pages.firstOrNull { it.id == at.pageId }
                ?: return listOf(LayoutChecker.Problem("$", "Page '${at.pageId}' is gone; try again"))
            val ids = page.sections.filterNot { it is DockSection }.map { it.id }
            val idx = ids.indexOf(at.sectionId)
            if (idx < 0 || (idx + steps).coerceIn(0, ids.size - 1) == idx) {
                return listOf(LayoutChecker.Problem("$", "Widget '$widgetId' is already at the ${if (steps < 0) "top" else "bottom"}"))
            }
        } else if (targetPageId != at.pageId) {
            val specs = widgets.current()
            val height = section?.let { LayoutEdits.estimateSectionRows(it, specs) } ?: 2
            val used = LayoutEdits.widgetRowsUsed(doc, targetPageId, specs)
            if (used + height > LayoutChecker.MAX_PAGE_ROWS) {
                return listOf(LayoutChecker.Problem("$", "That page is full"))
            }
        }
        return edit(label) { LayoutEdits.moveSection(it, at.sectionId, steps, targetPageId) }
    }

    /** Where one mini app sits on home at widget size, for the Widgets manager. */
    fun htmlPlacements(appId: String): List<LayoutEdits.WidgetPlacement> =
        base.value?.let { LayoutEdits.htmlPlacements(it, appId) }.orEmpty()

    /** Shows a mini app on home at widget size, with the same one-screenful overflow as widgets. */
    suspend fun placeHtml(label: String, appId: String, rows: Int = 2, pageId: String? = null): PlaceResult {
        htmlPlacements(appId).lastOrNull()?.let { return PlaceResult(emptyList(), it.pageId, alreadyPlaced = true) }
        val specs = widgets.current()
        val problems = edit(label) { LayoutEdits.placeHtml(it, appId, rows, pageId, specs)?.doc }
        if (problems.isNotEmpty()) return PlaceResult(problems, null)
        val at = htmlPlacements(appId).lastOrNull()?.pageId
        return PlaceResult(emptyList(), at)
    }

    /** The display name of a page ("Home 2", or the id when it has no title). */
    fun pageLabel(pageId: String): String =
        base.value?.pages?.firstOrNull { it.id == pageId }?.title ?: pageId

    /** Takes a mini app off home wherever it sits at widget size, in one undoable step. */
    suspend fun removeHtmlApp(label: String, appId: String): List<LayoutChecker.Problem> =
        edit(label) { LayoutEdits.removeHtmlApp(it, appId) }

    suspend fun undo(): Boolean = lock.withLock {
        val version = history.undo() ?: return false
        savePointer()
        repairDanglingRefs(version)
        publish()
        true
    }

    suspend fun redo(): Boolean = lock.withLock {
        val version = history.redo() ?: return false
        savePointer()
        repairDanglingRefs(version)
        publish()
        true
    }

    /** The saved document for one version, for the Refine flow. Null when unreadable. */
    suspend fun layoutFor(versionId: Long): LayoutDoc? = withContext(Dispatchers.IO) {
        runCatching {
            val row = dao.get(versionId) ?: return@runCatching null
            LayoutMigrations.decode(MasalaJson.strict.parseToJsonElement(row.json))
        }.getOrNull()
    }

    suspend fun select(versionId: Long): Boolean = lock.withLock {
        val version = history.select(versionId) ?: return false
        savePointer()
        repairDanglingRefs(version)
        publish()
        true
    }

    /**
     * An undo/redo/select can restore a placement whose widget is gone (delete isn't undone):
     * strip those refs in place so the next edit doesn't fail as "Unknown widget". No new
     * version, redo intact.
     */
    private suspend fun repairDanglingRefs(version: LayoutHistory.Version) {
        val cleaned = LayoutEdits.stripUnknownWidgets(version.layout, widgets.current().keys) ?: return
        val fixed = history.repairCurrent(cleaned) ?: return
        android.util.Log.w(TAG, "Stripped refs to missing widgets after history navigation")
        withContext(Dispatchers.IO) { dao.upsert(fixed.toEntity()) }
    }

    /** Renames one saved version. Blank labels are rejected; false when unknown. */
    suspend fun renameVersion(versionId: Long, label: String): Boolean = lock.withLock {
        val clean = label.trim()
        if (clean.isEmpty()) return false
        val renamed = history.rename(versionId, clean) ?: return false
        withContext(Dispatchers.IO) { dao.upsert(renamed.toEntity()) }
        publish()
        true
    }

    /**
     * Deletes one saved version, moving current to its predecessor (else successor).
     * False when unknown or when it is the last version.
     */
    suspend fun deleteVersion(versionId: Long): Boolean = lock.withLock {
        history.delete(versionId) ?: return false
        withContext(Dispatchers.IO) { dao.delete(listOf(versionId)) }
        savePointer()
        publish()
        true
    }

    /** Starts over from Classic, as a new version, so even this can be undone. */
    suspend fun resetToClassic(): List<LayoutChecker.Problem> =
        apply("Reset to Classic", ClassicLayoutBuilder.build(context, launcher.apps.value, emptyList()))

    fun isPinned(entry: AppEntry): Boolean = base.value?.let { doc -> favorites(doc).any { matches(it.action, entry) } } ?: false

    suspend fun pin(entry: AppEntry): List<LayoutChecker.Problem> {
        val doc = history.current?.layout ?: return emptyList()
        if (isPinned(entry)) return emptyList()
        val item = ClassicLayoutBuilder.appItem(ClassicLayoutBuilder.itemId(entry), entry)
        return insert(item)
    }

    /** Pins a generated mini-app into the favorites section, like an app pin. */
    suspend fun pinMiniApp(id: String, title: String): List<LayoutChecker.Problem> {
        if (isMiniAppPinned(id)) return emptyList()
        val item = com.samosaai.masala.core.contract.Item(
            id = "miniapp-$id",
            label = title.trim().ifEmpty { "Mini app" }.take(60),
            icon = com.samosaai.masala.core.contract.IconRef.Symbol("widgets"),
            action = MasalaAction.OpenMiniApp(id),
        )
        return insert(item)
    }

    /** Takes every home item that opens mini-app [id] off home, so deleting the app leaves no dead pin. */
    suspend fun unpinMiniApp(id: String): List<LayoutChecker.Problem> {
        val doc = history.current?.layout ?: return emptyList()
        val label = LayoutEdits.itemsWithFolders(doc.pages.flatMap { it.sections }.filterIsInstance<ItemSection>())
            .firstOrNull { (it.action as? MasalaAction.OpenMiniApp)?.id == id }?.label ?: return emptyList()
        return apply("Unpin $label", withoutMiniApp(doc, id))
    }

    /** Whether the mini-app already has an item on home, so pinning twice can't duplicate its id. */
    fun isMiniAppPinned(id: String): Boolean =
        base.value?.let { doc -> favorites(doc).any { (it.action as? MasalaAction.OpenMiniApp)?.id == id } } ?: false

    private suspend fun insert(item: com.samosaai.masala.core.contract.Item): List<LayoutChecker.Problem> {
        val doc = history.current?.layout ?: return emptyList()
        var added = false
        val pages = doc.pages.map { page ->
            page.copy(
                sections = page.sections.map { s ->
                    if (!added && s is GridSection && s.id == ClassicLayoutBuilder.FAVORITES) {
                        added = true
                        s.copy(items = s.items + item)
                    } else {
                        s
                    }
                },
            )
        }
        // No favorites section (a layout the model wrote): add one to the first page.
        val result = if (added) pages else pages.mapIndexed { i, page ->
            if (i != 0) page else page.copy(sections = listOf(GridSection(ClassicLayoutBuilder.FAVORITES, items = listOf(item))) + page.sections)
        }
        return apply("Pin ${item.label}", doc.copy(pages = result))
    }

    suspend fun unpin(entry: AppEntry): List<LayoutChecker.Problem> {
        val doc = history.current?.layout ?: return emptyList()
        // Out of folders too, or an app in one could be neither unpinned nor shown as pinned.
        return apply("Unpin ${entry.label}", LayoutEdits.removeWhere(doc, { it !is DockSection }) { matches(it.action, entry) })
    }

    /** Items outside the dock, folder children included. */
    private fun favorites(doc: LayoutDoc) =
        LayoutEdits.itemsWithFolders(doc.pages.flatMap { it.sections }.filterIsInstance<ItemSection>().filter { it !is DockSection })

    private fun matches(action: MasalaAction, entry: AppEntry) =
        action is MasalaAction.LaunchApp && action.packageName == entry.key.packageName && action.user == entry.key.user &&
            (action.activity == null || action.activity == entry.key.activity)

    /** What the checker knows about this phone. */
    suspend fun environment(): LayoutChecker.Environment {
        val installed = catalog.state.value.apps.groupBy({ it.key.packageName }, { it.key.user }).mapValues { it.value.toSet() }
        return LayoutChecker.Environment(
            installedApps = installed,
            skillIds = skills().getAllSkills().map { it.id }.toSet(),
            toolNames = toolNames(),
            modeIds = modes.patches.value.map { it.id }.toSet(),
            widgets = widgets.current(),
            streamSourceIds = streams.current().keys,
            miniAppIds = miniApps.list().map { it.id }.toSet(),
        )
    }

    // ---- first-frame cache ----

    private fun readCache(): ResolvedLayout? = runCatching {
        if (!cacheFile.isFile) return null
        val c = MasalaJson.strict.decodeFromString(Cached.serializer(), cacheFile.readText())
        ResolvedLayout(c.layout, c.widgetRows, emptyList())
    }.onFailure { Log.w(TAG, "Unreadable layout cache", it) }.getOrNull()

    private suspend fun writeCache(r: ResolvedLayout) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(MasalaJson.strict.encodeToString(Cached.serializer(), Cached(r.layout, r.widgetRows)))
            tmp.renameTo(cacheFile)
        }.onFailure { Log.w(TAG, "Can't write layout cache", it) }
    }

    internal companion object {
        private const val TAG = "LayoutRepository"
        private const val KEY_CURRENT = "current"
        private const val KEY_CELLS_FOLDED = "cells_folded"

        /** [doc] without the items that open mini-app [id], in any section, the dock and folders included. */
        fun withoutMiniApp(doc: LayoutDoc, id: String): LayoutDoc =
            LayoutEdits.removeWhere(doc) { (it.action as? MasalaAction.OpenMiniApp)?.id == id }
    }
}
