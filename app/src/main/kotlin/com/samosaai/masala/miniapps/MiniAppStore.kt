package com.samosaai.masala.miniapps

import com.samosaai.masala.data.MiniAppDao
import com.samosaai.masala.data.MiniAppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Generated HTML mini-apps (plan Phase 3, Lane D): CRUD over the `mini_apps` table. Rendering
 * lives in `MiniAppView`; the tools call here. HTML over [MAX_HTML_CHARS] is refused rather than
 * cut: slicing mid-script would ship a broken app and hide the reason.
 */
class MiniAppStore(private val dao: MiniAppDao) {
    val all: Flow<List<MiniAppEntity>> = dao.all()

    suspend fun get(id: String): MiniAppEntity? = withContext(Dispatchers.IO) { dao.get(id) }

    /** Every stored app, newest update first, for the tool listing and the settings page. */
    suspend fun list(): List<MiniAppEntity> = withContext(Dispatchers.IO) {
        dao.all().first()
    }

    /** Stores new HTML under a random id; returns the id, or null when the HTML is too large. */
    suspend fun create(title: String, html: String, icon: String? = null): String? = withContext(Dispatchers.IO) {
        if (html.length > MAX_HTML_CHARS) return@withContext null
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString().take(8)
        dao.upsert(
            MiniAppEntity(
                id = id,
                title = title.trim().ifEmpty { "Mini app" }.take(MAX_TITLE_CHARS),
                html = html,
                icon = MiniAppIcon.sanitize(icon),
                createdAt = now,
                updatedAt = now,
            )
        )
        id
    }

    /** Replaces the HTML, keeping the title and settings. False when unknown or too large. */
    suspend fun update(id: String, html: String, icon: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (html.length > MAX_HTML_CHARS) return@withContext false
        val row = dao.get(id) ?: return@withContext false
        // A missing or refused icon leaves the stored one alone; an accepted one replaces it.
        dao.upsert(row.copy(html = html, icon = MiniAppIcon.sanitize(icon) ?: row.icon, updatedAt = System.currentTimeMillis()))
        true
    }

    suspend fun rename(id: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val clean = title.trim()
        if (clean.isEmpty()) return@withContext false
        val row = dao.get(id) ?: return@withContext false
        dao.upsert(row.copy(title = clean.take(MAX_TITLE_CHARS), updatedAt = System.currentTimeMillis()))
        true
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) { dao.delete(id) }

    suspend fun setNetworkAllowed(id: String, allowed: Boolean): Unit = withContext(Dispatchers.IO) {
        dao.setNetworkAllowed(id, allowed, System.currentTimeMillis())
    }

    companion object {
        const val MAX_HTML_CHARS = 200_000
        const val MAX_TITLE_CHARS = 80
    }
}
