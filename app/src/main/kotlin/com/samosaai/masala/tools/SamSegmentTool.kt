package com.samosaai.masala.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.llm.AiSettingsStore
import com.samosaai.masala.tools.sam.Cutout
import com.samosaai.masala.tools.sam.MaskCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * SAM 3.1 segmentation (plan `docs/plans/media-tools.md`): sends an image + a
 * short noun phrase to `POST {base}/responses` (`model: "sam-3.1"`, same Bearer
 * key as everything else) and returns an annotated PNG (each object's decoded
 * mask painted as a tinted overlay with an edge outline, plus a very faint box),
 * one transparent-background cutout PNG per segmented object, plus a JSON
 * summary. A record whose mask fails to decode falls back to its box so the
 * run still produces something.
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
            val records = SamShared.parseRecords(text)
            val dir = File(context.filesDir, "generated").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            File(dir, "segment-$stamp.txt").writeText(text.take(200_000))
            val decoded = records.map { r ->
                runCatching { MaskCodec.decodeMask(r.maskHeight, r.maskWidth, r.maskPayload) }.getOrNull()
            }
            val annotated = runCatching { drawOverlay(pathOrUrl, records, decoded) }.getOrNull()
            val annotatedPath = annotated?.let {
                val f = File(dir, "segment-$stamp.png")
                f.outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
                if (it != null && !it.isRecycled()) it.recycle()
                f.absolutePath
            }
            // One cutout PNG per successfully decoded object: the source pixels
            // under its mask on a transparent background, ~1px feathered edge.
            val cutouts = runCatching { buildCutouts(pathOrUrl, records, decoded, dir, stamp) }.getOrDefault(emptyList())
            val maskOk = decoded.count { it != null }
            val summary = buildString {
                append("Found ${records.size} object${if (records.size == 1) "" else "s"} for '$phrase'")
                if (records.isNotEmpty()) {
                    append(" (masks ${records.map { "${it.maskHeight}x${it.maskWidth}" }.distinct().joinToString(", ")})")
                } else if (maskCount > 0 || boxes.isNotEmpty()) {
                    append(" (${boxes.size} boxes, $maskCount masks, unparsed)")
                }
                append(".")
                if (annotatedPath != null) append(" Overlay saved.")
                if (cutouts.isNotEmpty()) {
                    append(" ${cutouts.size} cutout${if (cutouts.size == 1) "" else "s"} saved (one per object, transparent background).")
                }
                if (maskOk < records.size && records.isNotEmpty()) {
                    append(" ${records.size - maskOk} mask${if (records.size - maskOk == 1) "" else "s"} didn't decode — box shown instead.")
                }
                records.take(5).forEachIndexed { i, r ->
                    val b = r.box
                    append(" #${r.id} [${b.x1},${b.y1},${b.x2},${b.y2}]")
                    if (decoded.getOrNull(i) != null) append(" mask ${r.maskHeight}x${r.maskWidth}")
                }
            }
            ActionResult.ok(
                summary,
                produced = listOfNotNull(
                    annotatedPath?.let { Produced("segmentation", "$phrase (${boxes.size})".take(80), it) },
                ) + cutouts.mapIndexed { n, path ->
                    Produced("image", "cutout ${n + 1}: $phrase".take(80), path)
                } + Produced("file", "segment-$stamp.txt", File(dir, "segment-$stamp.txt").absolutePath),
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

    /** Decodes the local source image, or null for remote/data URLs and unreadable files. */
    private fun loadSourceBitmap(pathOrUrl: String): Bitmap? {
        val t = pathOrUrl.trim()
        if (t.startsWith("http") || t.startsWith("data:")) return null
        val file = resolveLocal(t) ?: when (val r = resolver.resolveForRead(t)) {
            is ToolFiles.ResolveResult.Ok -> r.file
            else -> return null
        }
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    /**
     * One transparent-background cutout PNG per successfully decoded object
     * (none when objects overlap heavily is not special-cased: per-object
     * files compose the same scene, and each stays individually shareable).
     * Returns the saved absolute paths in record order.
     */
    private fun buildCutouts(
        pathOrUrl: String,
        records: List<SamShared.SamRecord>,
        decoded: List<ByteArray?>,
        dir: File,
        stamp: Long,
    ): List<String> {
        val src = loadSourceBitmap(pathOrUrl) ?: return emptyList()
        val srcArgb = src.copy(Bitmap.Config.ARGB_8888, false)
        if (src !== srcArgb && !src.isRecycled()) src.recycle()
        val w = srcArgb.width
        val h = srcArgb.height
        val pixels = IntArray(w * h)
        srcArgb.getPixels(pixels, 0, w, 0, 0, w, h)
        if (!srcArgb.isRecycled()) srcArgb.recycle()
        val out = mutableListOf<String>()
        records.forEachIndexed { i, r ->
            val raster = decoded.getOrNull(i) ?: return@forEachIndexed
            if (raster.size != r.maskHeight * r.maskWidth) return@forEachIndexed
            val s = SamShared.scaleBox(r.box, w, h)
            val cut = Cutout.applyCutout(
                pixels, w, h, raster, r.maskWidth, r.maskHeight,
                s[0].coerceIn(0f, w.toFloat()), s[1].coerceIn(0f, h.toFloat()),
                s[2].coerceIn(0f, w.toFloat()), s[3].coerceIn(0f, h.toFloat()),
            )
            if (cut.none { ((it ushr 24) and 0xFF) != 0 }) return@forEachIndexed
            val bmp = Bitmap.createBitmap(cut, w, h, Bitmap.Config.ARGB_8888)
            val f = File(dir, "segment-$stamp-cutout-${i + 1}.png")
            f.outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) }
            if (!bmp.isRecycled()) bmp.recycle()
            out.add(f.absolutePath)
        }
        return out
    }

    /**
     * Paints each decoded mask over the source image: a semi-transparent fill
     * in a per-object hue plus a thin opaque outline on the mask edge, with a
     * very faint box underneath. Records whose mask failed to decode fall back
     * to a plain red box. Mask rasters are box-local (their H×W tracks the box
     * size), so each one is stretched onto its box rect, which is mapped from
     * source pixels via the frame size the record reports (`w`/`h`).
     */
    private fun drawOverlay(
        pathOrUrl: String,
        records: List<SamShared.SamRecord>,
        decoded: List<ByteArray?>,
    ): Bitmap? {
        val src = loadSourceBitmap(pathOrUrl) ?: return null
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        if (src !== bmp && !src.isRecycled()) src.recycle()
        val canvas = Canvas(bmp)
        val faintBox = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, bmp.width / 400f)
            alpha = 48
        }
        val fallbackBox = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = maxOf(3f, bmp.width / 200f)
        }
        records.forEachIndexed { i, r ->
            val s = SamShared.scaleBox(r.box, bmp.width, bmp.height)
            val left = s[0].coerceIn(0f, bmp.width.toFloat())
            val top = s[1].coerceIn(0f, bmp.height.toFloat())
            val right = s[2].coerceIn(0f, bmp.width.toFloat())
            val bottom = s[3].coerceIn(0f, bmp.height.toFloat())
            if (right <= left || bottom <= top) return@forEachIndexed
            val raster = decoded.getOrNull(i)
            if (raster == null || raster.size != r.maskHeight * r.maskWidth) {
                canvas.drawRect(left, top, right, bottom, fallbackBox)
                return@forEachIndexed
            }
            canvas.drawRect(left, top, right, bottom, faintBox)
            val hue = (i * 137 % 360).toFloat()
            val fill = Color.HSVToColor(110, floatArrayOf(hue, 0.85f, 1f))
            val edge = Color.HSVToColor(255, floatArrayOf(hue, 0.9f, 1f))
            drawMaskFill(canvas, raster, r.maskWidth, r.maskHeight, left, top, right, bottom, fill)
            drawMaskEdge(canvas, raster, r.maskWidth, r.maskHeight, left, top, right, bottom, edge)
        }
        return bmp
    }

    /** Stretches the binary raster onto [dst] as one filtered bitmap draw. */
    private fun drawMaskFill(
        canvas: Canvas,
        raster: ByteArray,
        w: Int,
        h: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        fill: Int,
    ) {
        val pixels = IntArray(w * h) { i -> if (raster[i] != 0.toByte()) fill else 0 }
        val mask = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(mask, null, RectF(left, top, right, bottom), Paint().apply { isFilterBitmap = false })
        if (!mask.isRecycled()) mask.recycle()
    }

    /** Thin outline: strokes the scaled cell of every set pixel touching an unset one. */
    private fun drawMaskEdge(
        canvas: Canvas,
        raster: ByteArray,
        w: Int,
        h: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        edge: Int,
    ) {
        val cellW = (right - left) / w
        val cellH = (bottom - top) / h
        val paint = Paint().apply {
            color = edge
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, minOf(cellW, cellH) / 2f)
        }
        fun isSet(x: Int, y: Int): Boolean =
            x in 0 until w && y in 0 until h && raster[y * w + x] != 0.toByte()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!isSet(x, y)) continue
                if (isSet(x - 1, y) && isSet(x + 1, y) && isSet(x, y - 1) && isSet(x, y + 1)) continue
                canvas.drawRect(
                    left + x * cellW,
                    top + y * cellH,
                    left + (x + 1) * cellW,
                    top + (y + 1) * cellH,
                    paint,
                )
            }
        }
    }

    companion object {
        fun parseBoxes(wire: String): List<Box> =
            SamShared.parseRichBoxes(wire).map { Box(it.x1, it.y1, it.x2, it.y2) }
    }
}
