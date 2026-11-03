package com.samosaai.masala.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.llm.AiSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * SAM 3.1 segmentation (plan `docs/plans/media-tools.md`): sends an image + a
 * short noun phrase to `POST {base}/responses` (`model: "sam-3.1"`, same Bearer
 * key as everything else) and returns an annotated PNG (boxes drawn) plus a
 * JSON summary. Boxes are parsed from the wire format; mask payloads are
 * counted and kept in the raw text file, pixel-mask raster overlay is follow-up.
 */
class SamSegmentTool(
    private val context: Context,
    private val settings: AiSettingsStore,
    private val resolver: ToolFiles,
) {
    data class Box(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

    suspend fun segment(pathOrUrl: String, prompt: String): ActionResult =
        withContext(Dispatchers.IO) {
            val phrase = prompt.trim()
            if (phrase.isEmpty()) return@withContext ActionResult.error("Say what to find, e.g. 'red bicycle'.")
            val cfg = settings.imageConfig()
                ?: return@withContext ActionResult.error(
                    "Segmentation isn't set up yet. Pick a provider in Settings → Masala AI → Models & keys.",
                )
            // Segmentation is a different model from generation: the Responses API serves it
            // as sam-3.1 on the same host/key. Sending the image model here completes with
            // zero matches, so the model id is fixed (a wrong host fails loudly instead).
            val model = "sam-3.1"
            val imageUrl = runCatching { toDataUrl(pathOrUrl) }.getOrElse {
                return@withContext ActionResult.error(it.message ?: "Couldn't read that image.")
            }
            val text = runCatching { callSam(cfg.baseUrl, cfg.apiKey, model, imageUrl, phrase) }.getOrElse {
                return@withContext ActionResult.error(it.message ?: "Segmentation failed.")
            }
            val boxes = parseBoxes(text)
            val maskCount = SamShared.countMasks(text)
            val dir = File(context.filesDir, "generated").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            File(dir, "segment-$stamp.txt").writeText(text.take(200_000))
            val annotated = runCatching { drawBoxes(pathOrUrl, boxes) }.getOrNull()
            val annotatedPath = annotated?.let {
                val f = File(dir, "segment-$stamp.png")
                f.outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
                if (it != null && !it.isRecycled()) it.recycle()
                f.absolutePath
            }
            val summary = buildString {
                append("Found ${boxes.size} match${if (boxes.size == 1) "" else "es"} for '$phrase'")
                if (maskCount > 0) append(" ($maskCount masks)")
                append(".")
                boxes.take(5).forEachIndexed { i, b ->
                    append(" #${i + 1} [${b.x1},${b.y1},${b.x2},${b.y2}]")
                }
            }
            ActionResult.ok(
                summary,
                produced = listOfNotNull(
                    annotatedPath?.let { Produced("segmentation", "$phrase (${boxes.size})".take(80), it) },
                    Produced("file", "segment-$stamp.txt", File(dir, "segment-$stamp.txt").absolutePath),
                ),
            )
        }

    private fun resolveLocal(path: String): File? =
        appPrivateFile(context.filesDir.canonicalPath, context.cacheDir.canonicalPath, path)

    private fun toDataUrl(pathOrUrl: String): String {
        val t = pathOrUrl.trim()
        if (t.startsWith("data:image")) return t
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        val file = resolveLocal(t) ?: when (val r = resolver.resolveForRead(t)) {
            is ToolFiles.ResolveResult.PermissionNeeded -> throw IllegalStateException("Need file access to read that image.")
            is ToolFiles.ResolveResult.Error -> throw IllegalStateException(r.message)
            is ToolFiles.ResolveResult.Ok -> r.file
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw IllegalStateException("That file isn't a readable image.")
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 1024 || opts.outHeight / (sample * 2) >= 1024) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("That file isn't a readable image.")
        val scaled = if (maxOf(bmp.width, bmp.height) > 1024) {
            val s = 1024f / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        } else bmp
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        if (scaled !== bmp) scaled.recycle()
        if (bmp !== null && !bmp.isRecycled()) bmp.recycle()
        val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun callSam(baseUrl: String, apiKey: String, model: String, imageUrl: String, phrase: String): String =
        SamShared.postResponses(
            baseUrl, apiKey, model,
            buildJsonArray {
                add(SamShared.textPart(phrase))
                add(SamShared.imagePart(imageUrl))
            },
        )

    private fun drawBoxes(pathOrUrl: String, boxes: List<Box>): Bitmap? {
        val t = pathOrUrl.trim()
        src@ fun srcOf(): Bitmap? {
            if (t.startsWith("http") || t.startsWith("data:")) return null
            val file = resolveLocal(t) ?: when (val r = resolver.resolveForRead(t)) {
                is ToolFiles.ResolveResult.Ok -> r.file
                else -> return null
            }
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        val src = srcOf() ?: return null
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        if (src !== bmp && !src.isRecycled()) src.recycle()
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = maxOf(3f, bmp.width / 200f)
        }
        // Wire-format boxes are in the model's working resolution; the overlay scales
        // them onto the source bitmap by matching the max box edge to the bitmap edge.
        val maxEdge = boxes.flatMap { listOf(it.x2, it.y2) }.maxOrNull() ?: 0
        val scale = if (maxEdge > 0) maxOf(bmp.width, bmp.height) / maxEdge.toFloat() else 1f
        for (b in boxes) {
            canvas.drawRect(b.x1 * scale, b.y1 * scale, b.x2 * scale, b.y2 * scale, paint)
        }
        return bmp
    }

    companion object {
        fun parseBoxes(wire: String): List<Box> =
            SamShared.parseRichBoxes(wire).map { Box(it.x1, it.y1, it.x2, it.y2) }
    }
}
