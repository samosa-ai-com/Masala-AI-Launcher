package com.samosaai.masala.layout

import android.content.Context
import android.util.Log
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.core.layout.LayoutHistory
import com.samosaai.masala.core.layout.ResolveCache
import com.samosaai.masala.core.layout.ResolvedLayout
import com.samosaai.masala.core.migration.LayoutMigrations
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
) {
    enum class Source { CACHE, MINIMAL }

    @Serializable
    private data class Cached(val layout: LayoutDoc, val widgetRows: Map<String, Int>)

    private data class Base(val version: Long, val layout: LayoutDoc)

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
                resolveCache.resolve(base.version, base.layout, patches.filter { it.id in active }, snapshot)
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
        history = LayoutHistory(saved, pointer.getLong(KEY_CURRENT, -1).takeIf { it >= 0 })
        if (history.current == null) {
            // Fresh install, or nothing readable: Classic, once the app list is known.
            catalog.state.first { it.loaded }
            val classic = ClassicLayoutBuilder.build(context, launcher.apps.value, launcher.pinnedApps.value)
            lock.withLock { push("Classic", classic) }
        }
        publish()
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
        val problems = LayoutChecker.check(layout, environment())
        if (problems.isEmpty()) {
            push(label, layout)
            publish()
        }
        problems
    }

    suspend fun undo(): Boolean = lock.withLock { history.undo()?.also { savePointer(); publish() } != null }

    suspend fun redo(): Boolean = lock.withLock { history.redo()?.also { savePointer(); publish() } != null }

    suspend fun select(versionId: Long): Boolean = lock.withLock { history.select(versionId)?.also { savePointer(); publish() } != null }

    /** Starts over from Classic, as a new version, so even this can be undone. */
    suspend fun resetToClassic(): List<LayoutChecker.Problem> =
        apply("Reset to Classic", ClassicLayoutBuilder.build(context, launcher.apps.value, emptyList()))

    fun isPinned(entry: AppEntry): Boolean = base.value?.let { doc -> favorites(doc).any { matches(it.action, entry) } } ?: false

    suspend fun pin(entry: AppEntry): List<LayoutChecker.Problem> {
        val doc = history.current?.layout ?: return emptyList()
        if (isPinned(entry)) return emptyList()
        val item = ClassicLayoutBuilder.appItem(ClassicLayoutBuilder.itemId(entry), entry)
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
        return apply("Pin ${entry.label}", doc.copy(pages = result))
    }

    suspend fun unpin(entry: AppEntry): List<LayoutChecker.Problem> {
        val doc = history.current?.layout ?: return emptyList()
        val pages = doc.pages.map { page ->
            page.copy(
                sections = page.sections.map { s ->
                    if (s is ItemSection && s !is DockSection) s.withItems(s.items.filterNot { matches(it.action, entry) }) else s
                },
            )
        }
        return apply("Unpin ${entry.label}", doc.copy(pages = pages))
    }

    private fun favorites(doc: LayoutDoc) = doc.pages.flatMap { it.sections }.filterIsInstance<ItemSection>().filter { it !is DockSection }.flatMap { it.items }

    private fun matches(action: MasalaAction, entry: AppEntry) =
        action is MasalaAction.LaunchApp && action.packageName == entry.key.packageName && action.user == entry.key.user &&
            (action.activity == null || action.activity == entry.key.activity)

    /** What the checker knows about this phone. */
    fun environment(): LayoutChecker.Environment {
        val installed = catalog.state.value.apps.groupBy({ it.key.packageName }, { it.key.user }).mapValues { it.value.toSet() }
        return LayoutChecker.Environment(
            installedApps = installed,
            skillIds = skills().getAllSkills().map { it.id }.toSet(),
            toolNames = toolNames(),
            modeIds = modes.patches.value.map { it.id }.toSet(),
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

    private companion object {
        const val TAG = "LayoutRepository"
        const val KEY_CURRENT = "current"
    }
}
