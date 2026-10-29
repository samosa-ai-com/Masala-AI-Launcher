package com.samosaai.masala.secondbrain

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
import java.util.UUID

/**
 * The Second Brain repository (plan `docs/plans/second-brain-port.md`, P2): file-backed
 * memory over [SecondBrainStore], replacing the wiki table. Pages are detail records
 * (areas/people/notes); the model proposes, the user approves, approval writes a file.
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
    /** A page the model wants to add. Nothing is written until the user approves it. */
    data class Proposal(val id: String, val title: String, val markdown: String, val reason: String)

    private val _proposals = MutableStateFlow<List<Proposal>>(emptyList())
    val proposals: StateFlow<List<Proposal>> = _proposals.asStateFlow()

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

    /** Up to [limit] entries for a request: title matches first, then content hits. */
    suspend fun read(query: String, limit: Int = 3): List<BrainFile> = withContext(Dispatchers.IO) {
        val snapshot = refreshSnapshot()
        val files = snapshot.details()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()
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

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val ok = store.deleteEntry(id)
        if (ok) refresh()
        ok
    }

    /** Queues an entry for approval. Returns null when it is empty or fails the schema. */
    fun propose(title: String, markdown: String, reason: String): Proposal? {
        val cleanTitle = title.trim()
        val body = markdown.trim()
        if (cleanTitle.isEmpty() || body.isBlank()) return null
        val check = store.validateRecord("$cleanTitle.md", recordText(cleanTitle, body))
        if (!check.valid) return null
        val proposal = Proposal(UUID.randomUUID().toString(), cleanTitle, body, reason.trim())
        _proposals.value = (_proposals.value.filterNot {
            it.title.equals(cleanTitle, ignoreCase = true)
        } + proposal).takeLast(MAX_PENDING)
        return proposal
    }

    /** Writes an approved proposal (same-title update) and records it in the action log. */
    suspend fun approve(id: String): BrainFile? {
        val proposal = _proposals.value.firstOrNull { it.id == id } ?: return null
        val file = withContext(Dispatchers.IO) { store.upsertByTitle(proposal.title, proposal.markdown) }
        _proposals.value = _proposals.value.filterNot { it.id == id }
        audit("memory_approve", proposal.title)
        refresh()
        return withContext(Dispatchers.IO) {
            refreshSnapshot().details().firstOrNull { it.absolutePath == file.absolutePath }
        }
    }

    fun dismiss(id: String) {
        _proposals.value = _proposals.value.filterNot { it.id == id }
    }

    /** Every detail record as one Markdown document, for export (bodies only, no frontmatter). */
    suspend fun exportMarkdown(): String = withContext(Dispatchers.IO) {
        refreshSnapshot().details().joinToString("\n\n") { "# ${titleOf(it)}\n\n${bodyOf(it.content)}" }
    }

    /** The first body line, for list previews (frontmatter skipped). */
    fun previewOf(file: BrainFile): String =
        bodyOf(file).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

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

    private fun recordText(title: String, body: String): String = buildString {
        appendLine("---")
        appendLine("title: $title")
        appendLine("area: Personal")
        appendLine("type: note")
        appendLine("updated: 2000-01-01")
        appendLine("---")
        appendLine()
        append(body)
    }

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
        const val MAX_PENDING = 5
    }
}
