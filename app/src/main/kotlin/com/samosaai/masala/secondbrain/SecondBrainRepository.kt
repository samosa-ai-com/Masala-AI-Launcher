package com.samosaai.masala.secondbrain

import androidx.compose.ui.geometry.Offset
import com.samosaai.masala.data.WikiDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * The Second Brain repository (plan `docs/plans/second-brain-port.md`, P2): file-backed
 * memory over [SecondBrainStore], replacing the wiki table. Pages are detail records
 * (areas/people/notes). The agent writes through the gated store API; every change can be undone.
 *
 * Flows re-read on every mutation ([refresh]); the agent prompt reads the cached
 * snapshot so prompt building never touches disk.
 */
class SecondBrainRepository(
    private val scope: CoroutineScope,
    private val store: SecondBrainStore,
    private val legacy: WikiDao? = null,
    private val audit: (action: String, detail: String) -> Unit = { _, _ -> },
) {
    /** A change the agent just made (D1: applied at once), shown with Undo until dismissed. */
    data class Remembered(val id: String, val action: String, val undoId: String)

    /** Recent agent changes, oldest first. */
    private val changes = MutableStateFlow<List<Remembered>>(emptyList())
    private val _remembered = MutableStateFlow<List<Remembered>>(emptyList())

    /**
     * One card per file, newest first: its latest change. Undo steps that file back one change, and
     * the change before it becomes the card, so an old card can never wipe out newer edits.
     */
    val remembered: StateFlow<List<Remembered>> = _remembered.asStateFlow()

    private fun setChanges(list: List<Remembered>) {
        changes.value = list.takeLast(MAX_CHANGES)
        _remembered.value = changes.value.asReversed().distinctBy { it.id }.take(MAX_REMEMBERED)
    }

    private val tick = MutableStateFlow(0)

    @Volatile
    private var cached: SecondBrainSnapshot? = null

    /** Detail records (areas/people/notes), for the list screen. */
    val pages: Flow<List<BrainFile>> = tick
        .mapLatest { refreshSnapshot().details() }
        .flowOn(Dispatchers.IO)

    /** Entry titles for the system prompt and the layout prompt. */
    val titles: StateFlow<List<String>> = tick
        .mapLatest { refreshSnapshot().titles() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Seeds the tree and imports legacy wiki rows once. Safe to call repeatedly. */
    suspend fun ensure() {
        withContext(Dispatchers.IO) {
            store.ensureDefaults()
            migrateLegacy()
        }
        refresh()
    }

    /** The prompt block, from cache so prompt building never touches disk. */
    fun promptNow(): SecondBrainPrompt {
        val snapshot = cached
        if (snapshot == null) return SecondBrainPrompt(null, null, false, false, emptyList())
        return store.buildPrompt(snapshot)
    }

    /**
     * Up to [limit] entries for a request: title matches first, then content hits. The user card
     * (the profile) is found by its id or name ("user.md", "user", "profile"), not by its text.
     */
    suspend fun read(query: String, limit: Int = 3): List<BrainFile> = withContext(Dispatchers.IO) {
        val snapshot = refreshSnapshot()
        val files = snapshot.details()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()
        snapshot.userCard?.takeIf { q in USER_CARD_NAMES }?.let { return@withContext listOf(it) }
        val words = q.split(Regex("\\W+")).filter { it.length > 2 }
        fun score(file: BrainFile): Int {
            val title = titleOf(file).lowercase()
            val body = file.content.lowercase()
            return when {
                title == q -> 100
                title.contains(q) || q.contains(title) -> 80
                words.any { it in title } -> 60 + words.count { it in title }
                words.any { it in body } -> 20 + words.count { it in body }
                else -> 0
            }
        }
        files.map { it to score(it) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** One vault file by path, capped; [MemoryProtocol.PATH] returns the protocol. */
    suspend fun readPath(path: String): SecondBrainStore.CappedText? {
        if (path.trim().equals(MemoryProtocol.PATH, ignoreCase = true)) return SecondBrainStore.CappedText(MemoryProtocol.TEXT, false)
        return withContext(Dispatchers.IO) { store.readText(path) }
    }

    /** Whether [path] names a dated note (history rule: changing one asks first). */
    fun isDatedNote(path: String): Boolean = store.recordDirKind(path.trim().trimStart('/')) != null && store.isDatedNote(path)

    /** Matching lines across the vault, `path:line: text`. */
    suspend fun search(query: String, limit: Int = 20): List<String> =
        withContext(Dispatchers.IO) { store.search(query, limit) }

    suspend fun get(id: String): BrainFile? = withContext(Dispatchers.IO) {
        refreshSnapshot().details().firstOrNull { idOf(it) == id }
    }

    /** Creates or updates an entry; a blank [id] upserts by title (same-title update). */
    suspend fun save(id: String?, title: String, markdown: String): BrainFile? = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || markdown.isBlank()) return@withContext null
        val file = if (id.isNullOrBlank()) {
            store.upsertByTitle(cleanTitle, markdown.trim())
        } else {
            store.writeEntry(id, cleanTitle, markdown.trim()) ?: return@withContext null
        }
        refresh()
        refreshSnapshot().details().firstOrNull { it.absolutePath == file.absolutePath }
    }

    suspend fun delete(id: String): Boolean {
        val ok = withContext(Dispatchers.IO) { store.deleteEntry(id) }
        if (ok) {
            refresh()
            onNoteDeleted(id)
        }
        return ok
    }

    /** Gated write at a vault path ([SecondBrainStore.writeRecord]); a success is logged and refreshes the flows. */
    suspend fun write(path: String, content: String, append: Boolean = false, allowDuplicate: Boolean = false) =
        changed("memory_write") { store.writeRecord(path, content, append, allowDuplicate) }

    /** Gated exact-text edit ([SecondBrainStore.editRecord]). */
    suspend fun edit(path: String, oldText: String, newText: String, replaceAll: Boolean = false) =
        changed("memory_edit") { store.editRecord(path, oldText, newText, replaceAll) }

    /** Copies a raw drop into raw/ ([SecondBrainStore.fileRaw]); undo removes the copy. */
    suspend fun fileRaw(name: String, size: Long? = null, open: () -> java.io.InputStream) =
        changed("memory_file_raw") { store.fileRaw(name, size = size, open = open) }

    /** Temp and cache paths: a raw drop from there is always copied, never linked. */
    fun isEphemeralPath(path: String): Boolean = store.isEphemeralPath(path)

    /** Deletes a record or raw file ([SecondBrainStore.deleteRecord]). */
    suspend fun remove(path: String) = changed("memory_delete") { store.deleteRecord(path) }
        .also { if (it.ok) onNoteDeleted(it.id) }

    /** Called with the vault id of every deleted file, so a task pointing at it can let go (S5). */
    @Volatile
    var onNoteDeleted: suspend (String) -> Unit = {}

    /** Writes `task_ref` into a note (shown as a change with Undo). */
    suspend fun linkTask(path: String, taskId: String) = changed("memory_link_task") { store.setTaskRef(path, taskId) }

    /** Clears a note's `task_ref` because its task went away; not shown as a card. */
    suspend fun clearTaskRef(path: String): Boolean {
        val outcome = withContext(Dispatchers.IO) { store.setTaskRef(path, null) }
        if (outcome.ok) {
            audit("memory_unlink_task", outcome.id)
            refresh()
        }
        return outcome.ok
    }

    /** A note's `task_ref`, or null. */
    suspend fun taskRefOf(path: String): String? = withContext(Dispatchers.IO) {
        store.readText(path, SecondBrainStore.FRONTMATTER_SCAN_CHARS)?.text?.let { store.frontmatterOf(it) }?.get("task_ref")?.takeIf { it.isNotBlank() }
    }

    /** Puts back the file a change replaced, created or deleted. */
    suspend fun undo(undoId: String): Boolean {
        val target = withContext(Dispatchers.IO) { store.undoTarget(undoId) }
        val ok = withContext(Dispatchers.IO) { store.undo(undoId) }
        if (ok) {
            audit("memory_undo", target.orEmpty())
            refresh()
        }
        // Cleared either way: a change whose journal entry is gone (trimmed past 50, or undone twice) can
        // never be undone, so its card would only fail again.
        setChanges(changes.value.filterNot { it.undoId == undoId })
        return ok
    }

    private suspend fun changed(
        action: String,
        block: () -> SecondBrainStore.WriteOutcome,
    ): SecondBrainStore.WriteOutcome {
        val outcome = withContext(Dispatchers.IO) { block() }
        if (outcome.ok) {
            audit(action, outcome.id)
            outcome.undoId?.let { undoId ->
                setChanges(changes.value + Remembered(outcome.id, action, undoId))
            }
            refresh()
        }
        return outcome
    }

    /** Hides a change's card; the change itself stays. */
    fun dismissRemembered(undoId: String) {
        val id = changes.value.firstOrNull { it.undoId == undoId }?.id ?: return
        setChanges(changes.value.filterNot { it.id == id })
    }

    /** Every detail record as one Markdown document, for export (bodies only, no frontmatter). */
    suspend fun exportMarkdown(): String = withContext(Dispatchers.IO) {
        refreshSnapshot().details().joinToString("\n\n") { "# ${titleOf(it)}\n\n${bodyOf(it.content)}" }
    }

    /** A fresh snapshot, for the screen and health checks. Never moves the tick. */
    suspend fun snapshot(): SecondBrainSnapshot = withContext(Dispatchers.IO) {
        store.loadSnapshot().also { cached = it }
    }

    /**
     * The graph plus its 3D layout: the flat force-directed cloud turned onto
     * its side (spread runs front-to-back, depth across), confined to the
     * brain shell. Computed off the main thread.
     */
    suspend fun preparedGraph(): PreparedGraph = withContext(Dispatchers.Default) {
        val graph = withContext(Dispatchers.IO) { store.loadSnapshot().also { cached = it } }
            .let { store.buildGraph(it) }
        val index = graph.nodes.indices.associateBy({ graph.nodes[it].id }, { it })
        val pairs = graph.edges.mapNotNull { e ->
            val a = index[e.fromId]
            val b = index[e.toId]
            if (a != null && b != null) a to b else null
        }
        val iterations = (16000 / maxOf(1, graph.nodes.size)).coerceIn(40, 160)
        val points = store.forceLayout(graph.nodes.size, pairs, iterations)
        val depths = List(graph.nodes.size) { store.nodeZ(it) }
        // Turn the flat layout cloud onto its side: spread runs front-to-back
        // (z) so the starting side view shows the full cloud, depth runs across.
        val sideways = points.indices.map { i -> Offset(depths[i], points[i].y) }
        val sidewaysDepths = points.indices.map { i -> points[i].x }
        val (confined, confinedZ) = store.confineToBrain(sideways, sidewaysDepths)
        PreparedGraph(graph, confined, confinedZ)
    }

    /** The vault file behind a graph node id, or null when it is gone. */
    fun findIn(snapshot: SecondBrainSnapshot, id: String): BrainFile? =
        snapshot.allMarkdown().firstOrNull { store.relativeId(it) == id }
            ?: snapshot.raw.firstOrNull { store.relativeId(it) == id }

    /** The snapshot narrowed to [query]; the screen debounces and runs it off-main. */
    fun filter(snapshot: SecondBrainSnapshot, query: String): SecondBrainSnapshot =
        store.filterSnapshot(snapshot, query)

    /** Vault health: links, schema, unscanned files, stale raw drops, and task links both ways. Never moves the tick. */
    suspend fun health(taskLinks: List<TaskLink> = emptyList()): BrainHealth = withContext(Dispatchers.IO) {
        val snapshot = store.loadSnapshot().also { cached = it }
        store.checkHealth(snapshot, taskLinks)
    }

    /** Rewrites user.md from profile fields, then refreshes. */
    suspend fun syncUser(fields: Map<String, String>) {
        withContext(Dispatchers.IO) { store.syncUser(fields) }
        refresh()
    }

    /** Text previewable in the viewer. */
    fun isText(file: BrainFile): Boolean = store.isTextFile(file.name, file.content)

    /** Whether the screen offers Edit/Delete for a vault id. */
    fun isEditable(id: String): Boolean = store.isEditable(id)

    /** The first body line, for list previews (frontmatter skipped). */
    fun previewOf(file: BrainFile): String =
        bodyOf(file).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

    /** Profile fields from user.md bullets (`- name: …`), for the editor. */
    suspend fun profile(): Map<String, String> = withContext(Dispatchers.IO) {
        val card = refreshSnapshot().userCard?.content ?: return@withContext emptyMap()
        card.lines().mapNotNull { line ->
            val t = line.trim()
            if (!t.startsWith("- ")) return@mapNotNull null
            val kv = t.removePrefix("- ").split(":", limit = 2)
            if (kv.size != 2) return@mapNotNull null
            val v = kv[1].trim()
            if (kv[0].isBlank() || v.isEmpty() || v.startsWith("(")) null else kv[0].trim() to v
        }.toMap()
    }

    /** The editable body (frontmatter stripped). */
    fun bodyOf(file: BrainFile): String = bodyOf(file.content)

    private fun bodyOf(content: String): String {
        val lines = content.lines()
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) return lines.drop(2 + end).joinToString("\n").trim()
        }
        return content.trim()
    }

    fun idOf(file: BrainFile): String = store.relativeId(file)

    fun titleOf(file: BrainFile): String =
        store.frontmatterOf(file.content)?.get("title")?.takeIf { it.isNotBlank() }
            ?: file.name.removeSuffix(".md").removeSuffix(".markdown")

    private suspend fun refresh() {
        withContext(Dispatchers.IO) { refreshSnapshot() }
        tick.value++
    }

    private fun refreshSnapshot(): SecondBrainSnapshot =
        store.loadSnapshot().also { cached = it }

    private fun SecondBrainSnapshot.details(): List<BrainFile> = areas + people + notes

    private fun SecondBrainSnapshot.titles(): List<String> = details().map { titleOf(it) }

    private suspend fun migrateLegacy() {
        val dao = legacy ?: return
        try {
            // Every row is attempted (upsert dedupes by title); only imported rows are deleted,
            // so a crash mid-migration resumes where it stopped instead of abandoning rows.
            for (row in dao.all().first()) {
                runCatching {
                    if (row.title.isNotBlank() && row.markdown.isNotBlank()) {
                        store.upsertByTitle(row.title, row.markdown)
                        dao.delete(row.id)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val MAX_REMEMBERED = 5
        /** Changes kept for step-by-step undo from the cards. */
        const val MAX_CHANGES = 20
        private val USER_CARD_NAMES = setOf("user.md", "user", "profile", "user card", "user profile")
    }
}
