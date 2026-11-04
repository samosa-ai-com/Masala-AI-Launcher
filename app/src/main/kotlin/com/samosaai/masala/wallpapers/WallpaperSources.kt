package com.samosaai.masala.wallpapers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a routine's `source` into a file [com.samosaai.masala.tools.WallpaperTool] can set.
 * Three forms:
 *
 * - `asset:<file>` — a bundled wallpaper, extracted once into the app's files dir so it keeps a
 *   stable path (the cache can be cleared between runs).
 * - `id:<n>` — a wallpaper the user generated, resolved through the catalog.
 * - a readable `/path` or an http(s) url, passed through to the tool.
 */
object WallpaperSources {

    /** The file to set, or null when the source names nothing on this phone. */
    suspend fun resolve(
        context: Context,
        source: String,
        generated: suspend (Long) -> File? = { null },
    ): File? = withContext(Dispatchers.IO) {
        when {
            source.startsWith("asset:") -> bundled(context, source.removePrefix("asset:"))
            source.startsWith("id:") -> source.removePrefix("id:").toLongOrNull()?.let { generated(it) }
            source.startsWith("/") -> File(source).takeIf { it.canRead() }
            else -> null
        }
    }

    /** The bundled file, copied out of the APK once. */
    suspend fun bundled(context: Context, fileName: String): File? = withContext(Dispatchers.IO) {
        if (fileName.isBlank() || '/' in fileName) return@withContext null
        val dir = File(context.filesDir, "wallpapers/bundled").apply { mkdirs() }
        val out = File(dir, fileName)
        if (out.length() > 0) return@withContext out
        runCatching {
            context.assets.open("wallpapers/$fileName").use { input ->
                val temp = File(dir, "$fileName.part")
                temp.outputStream().use { output -> input.copyTo(output) }
                if (!temp.renameTo(out)) {
                    out.outputStream().use { output -> temp.inputStream().use { it.copyTo(output) } }
                    temp.delete()
                }
            }
        }.getOrNull()
        out.takeIf { it.length() > 0 }
    }
}
