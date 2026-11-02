package com.samosaai.masala.wallpapers

import com.samosaai.masala.data.WallpaperDao
import com.samosaai.masala.data.WallpaperEntity
import java.io.File

/** One catalog row with its file resolved. */
data class Wallpaper(val id: Long, val file: File, val prompt: String, val style: String, val createdAt: Long)

/**
 * The wallpaper catalog (plan `docs/plans/wallpaper-generation.md`): prompts live in Room,
 * pictures in `filesDir/wallpapers/`. Deleting a row deletes its file.
 */
class WallpaperStore(private val dao: WallpaperDao, private val dir: File) {
    suspend fun add(fileName: String, prompt: String, style: String): Long =
        dao.insert(WallpaperEntity(fileName = fileName, prompt = prompt, style = style, createdAt = System.currentTimeMillis()))

    suspend fun all(): List<Wallpaper> = dao.all().map { it.wallpaper() }

    suspend fun search(query: String): List<Wallpaper> {
        val q = query.trim()
        if (q.isEmpty()) return all()
        return dao.search(q).map { it.wallpaper() }
    }

    suspend fun delete(id: Long) {
        dao.all().firstOrNull { it.id == id }?.let { runCatching { File(dir, it.fileName).delete() } }
        dao.delete(id)
    }

    fun fileFor(fileName: String): File = File(dir, fileName)

    private fun WallpaperEntity.wallpaper() = Wallpaper(id, File(dir, fileName), prompt, style, createdAt)
}
