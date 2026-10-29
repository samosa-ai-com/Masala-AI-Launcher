package com.samosaai.masala.wiki

import com.samosaai.masala.data.WikiDao
import com.samosaai.masala.data.WikiPageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/** A page the model wants to add. Nothing is written until the user approves it. */
data class WikiProposal(val id: String, val title: String, val markdown: String, val reason: String)

/**
 * The Personal Wiki (arch §3.5): short Markdown pages the user can read, edit and delete. The model
 * sees only the titles and fetches pages with `wiki_read`; it writes through [propose], which waits in
 * memory for the user's Approve. Nothing is filled in from messages or notifications.
 *
 * [audit] is told about every approved write (the app records it in the `ActionLog`).
 */
class WikiRepository(
    scope: CoroutineScope,
    private val dao: WikiDao,
    private val audit: (action: String, detail: String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val pages: Flow<List<WikiPageEntity>> = dao.all()

    /** Page titles for the system prompt. */
    val titles: StateFlow<List<String>> = dao.all().map { all -> all.map { it.title } }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _proposals = MutableStateFlow<List<WikiProposal>>(emptyList())
    val proposals: StateFlow<List<WikiProposal>> = _proposals.asStateFlow()

    suspend fun get(id: String) = dao.get(id)

    /** Adds or replaces a page the user wrote. A blank [id] makes a new page. */
    suspend fun save(id: String?, title: String, markdown: String, source: String = SOURCE_USER): WikiPageEntity {
        val page = WikiPageEntity(id?.takeIf { it.isNotBlank() } ?: newId(), title.trim().take(MAX_TITLE), markdown.take(MAX_BODY), source, clock())
        dao.upsert(page)
        return page
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** Up to [limit] pages for a request: title matches first, then pages whose text has one of the words. */
    suspend fun read(query: String, limit: Int = 3): List<WikiPageEntity> = WikiSearch.rank(query, dao.all().first()).take(limit)

    /** Queues a page for approval. Returns null if it is empty; at most [MAX_PENDING] wait at once. */
    fun propose(title: String, markdown: String, reason: String): WikiProposal? {
        val cleanTitle = title.trim().take(MAX_TITLE)
        if (cleanTitle.isEmpty() || markdown.isBlank()) return null
        val proposal = WikiProposal(newId(), cleanTitle, markdown.trim().take(MAX_BODY), reason.trim().take(MAX_REASON))
        _proposals.value = (_proposals.value.filterNot { it.title.equals(cleanTitle, ignoreCase = true) } + proposal).takeLast(MAX_PENDING)
        return proposal
    }

    /** Writes an approved proposal. A page with the same title is updated rather than duplicated. */
    suspend fun approve(id: String): WikiPageEntity? {
        val proposal = _proposals.value.firstOrNull { it.id == id } ?: return null
        val existing = dao.all().first().firstOrNull { it.title.equals(proposal.title, ignoreCase = true) }
        val page = save(existing?.id, proposal.title, proposal.markdown, SOURCE_AGENT)
        _proposals.value = _proposals.value.filterNot { it.id == id }
        audit("wiki_approve", proposal.title)
        return page
    }

    fun dismiss(id: String) {
        _proposals.value = _proposals.value.filterNot { it.id == id }
    }

    /** Every page as one Markdown document, for export. */
    suspend fun exportMarkdown(): String =
        dao.all().first().joinToString("\n\n") { "# ${it.title}\n\n${it.markdown.trim()}" }

    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_AGENT = "agent"
        const val MAX_TITLE = 80
        const val MAX_BODY = 4_000
        const val MAX_REASON = 200
        const val MAX_PENDING = 5
    }
}

object WikiSearch {
    fun rank(query: String, pages: List<WikiPageEntity>): List<WikiPageEntity> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\W+")).filter { it.length > 2 }
        fun score(p: WikiPageEntity): Int {
            val title = p.title.lowercase()
            val body = p.markdown.lowercase()
            return when {
                title == q -> 100
                title.contains(q) || q.contains(title) -> 80
                words.any { it in title } -> 60 + words.count { it in title }
                words.any { it in body } -> 20 + words.count { it in body }
                else -> 0
            }
        }
        return pages.map { it to score(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }
}
