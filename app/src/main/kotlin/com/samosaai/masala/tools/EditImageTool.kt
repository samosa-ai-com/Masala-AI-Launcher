package com.samosaai.masala.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.core.tool.ToolFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Absolute app-private paths (chat Produced refs) bypass the file-workspace resolver.
 * Pure path check so unit tests cover it without a Context: the caller checks existence.
 */
internal fun appPrivateFile(filesCanonical: String, cacheCanonical: String, path: String): java.io.File? {
    val f = java.io.File(path)
    if (!f.isAbsolute) return null
    val canonical = runCatching { f.canonicalPath }.getOrNull() ?: return null
    return if (canonical.startsWith(filesCanonical) ||
        (cacheCanonical.isNotEmpty() && canonical.startsWith(cacheCanonical))
    ) f else null
}

/**
 * Local image edits (plan `docs/plans/media-tools.md`): rotate / crop-square /
 * resize / grayscale on a file already on disk. No network, no extra config —
 * the fallback the user chose when no hosted edit endpoint is available.
 */
class EditImageTool(
    private val context: Context,
    private val resolver: ToolFiles,
) {
    suspend fun edit(path: String, action: String, arg: String? = null): ActionResult =
        withContext(Dispatchers.IO) {
            val file = resolveLocal(path) ?: when (val r = resolver.resolveForRead(path)) {
                is ToolFiles.ResolveResult.PermissionNeeded -> return@withContext r.result
                is ToolFiles.ResolveResult.Error -> return@withContext ActionResult.error(r.message)
                is ToolFiles.ResolveResult.Ok -> r.file
            }
            resolver.checkReadPermission(file)?.let { return@withContext it }
            if (!file.canRead()) return@withContext ActionResult.error("Couldn't read '${file.name}'.")
            val src = BitmapFactory.decodeFile(file.absolutePath)
                ?: return@withContext ActionResult.error("That file isn't a readable image.")
            val out = runCatching { applyAction(src, action.lowercase(), arg) }.getOrElse {
                if (src != null && !src.isRecycled()) src.recycle()
                return@withContext ActionResult.error(it.message ?: "Couldn't edit the image.")
            }
            val dir = File(context.filesDir, "generated").apply { mkdirs() }
            val dest = File(dir, "edit-${System.currentTimeMillis()}.png")
            val ok = runCatching {
                dest.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.getOrDefault(false)
            if (src != out && !src.isRecycled()) src.recycle()
            if (!ok || !dest.exists()) {
                if (!out.isRecycled()) out.recycle()
                return@withContext ActionResult.error("Couldn't save the edited image.")
            }
            if (!out.isRecycled()) out.recycle()
            ActionResult.ok(
                "Edited the image ($action).",
                produced = listOf(Produced("image", "${action}: ${file.name}".take(80), dest.absolutePath)),
            )
        }

    /**
     * Produced refs from generate_image/segment_image are absolute paths under the app's own
     * files: the file-workspace resolver can't see them, so they are used directly after
     * checking they stay inside the app's private dirs.
     */
    private fun resolveLocal(path: String): File? =
        appPrivateFile(context.filesDir.canonicalPath, context.cacheDir.canonicalPath, path)
            ?.takeIf { it.exists() }

    private fun applyAction(src: Bitmap, action: String, arg: String?): Bitmap = when (action) {
        "rotate" -> {
            val deg = arg?.toFloatOrNull() ?: 90f
            val m = Matrix().apply { postRotate(deg) }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
        "crop_square" -> {
            val side = minOf(src.width, src.height)
            val x = (src.width - side) / 2
            val y = (src.height - side) / 2
            Bitmap.createBitmap(src, x, y, side, side)
        }
        "resize" -> {
            val maxSide = arg?.toIntOrNull()?.coerceIn(64, 4096) ?: 1024
            val scale = maxSide / maxOf(src.width, src.height).toFloat()
            if (scale >= 1f) src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
            else Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        }
        "grayscale" -> {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
        else -> throw IllegalArgumentException("Unknown edit '$action'. Use rotate, crop_square, resize or grayscale.")
    }
}
