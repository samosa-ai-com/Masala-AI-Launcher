package com.samosaai.masala.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.llm.AiSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import java.io.File

/**
 * SAM 3.1 video segmentation: sends a video file + a short noun phrase to
 * `POST {base}/responses` (`model: "sam-3.1"`, same Bearer key as everything
 * else) and returns a contact-sheet PNG (up to 3 sampled frames with that
 * frame's boxes drawn) plus a JSON sidecar with the per-frame boxes.
 *
 * The reply uses the same compact token format as images, with per-frame
 * markers (`<0f>…<|box;…|>…<1f>…`) tracking the object across the frames the
 * API samples. Play-safe: frames come from [MediaMetadataRetriever], one at a
 * time, bitmaps recycled — no ffmpeg, no new permissions.
 */
class SegmentVideoTool(
    private val context: Context,
    private val settings: AiSettingsStore,
    private val resolver: ToolFiles,
) {
    suspend fun segment(path: String, prompt: String, maxFrames: Int? = null, mode: String? = null): ActionResult =
        withContext(Dispatchers.IO) {
            val phrase = prompt.trim()
            if (phrase.isEmpty()) return@withContext ActionResult.error("Say what to find, e.g. 'red bicycle'.")
            val cfg = settings.imageConfig()
                ?: return@withContext ActionResult.error(
                    "Segmentation isn't set up yet. Pick a provider in Settings → Masala AI → Models & keys.",
                )
            val hint = SamShared.clampMaxFrames(maxFrames)
            val file = runCatching { resolveVideoFile(path) }.getOrElse {
                return@withContext ActionResult.error(it.message ?: "Couldn't read that video.")
            }
            SamShared.checkVideoSize(file.length())?.let { return@withContext ActionResult.error(it) }
            // One probe frame first, so an unreadable video fails before the network call.
            val durationMs = readDurationMs(file)
            if (durationMs <= 0 && !probeFrame(file, 0L)) {
                return@withContext ActionResult.error(
                    "Couldn't read any frames from '${file.name}'. It may be corrupt or DRM-protected.",
                )
            }
            val mime = SamShared.videoMimeFor(file.name)
            val dataUrl = "data:$mime;base64," + SamShared.encodeBase64(file.readBytes())
            val text = runCatching {
                SamShared.postResponses(
                    cfg.baseUrl, cfg.apiKey, SamShared.SAM_MODEL,
                    buildJsonArray {
                        add(SamShared.textPart(phrase))
                        add(SamShared.videoPart(dataUrl))
                    },
                )
            }.getOrElse {
                return@withContext ActionResult.error(it.message ?: "Segmentation failed.")
            }
            val groups = SamShared.parseVideoFrames(text)
            val hits = groups.filter { it.boxes.isNotEmpty() }
            if (hits.isEmpty()) return@withContext ActionResult.error("No '$phrase' found in that clip.")
            val dir = File(context.filesDir, "generated").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            val jsonFile = File(dir, "segment-video-$stamp.json")
            jsonFile.writeText(SamShared.framesJson(hits).take(200_000))
            val sheetPath = runCatching { buildContactSheet(file, durationMs, hits, dir, stamp) }.getOrNull()
            val totalBoxes = hits.sumOf { it.boxes.size }
            val summary = buildString {
                append("Tracked '$phrase' in ${hits.size} of ${groups.size} sampled frames ($totalBoxes boxes).")
                append(" Note: max_frames is a hint — the API samples its own frames (asked $hint, got ${groups.size}).")
                if (!mode.isNullOrBlank()) append(" Mode '$mode' is recorded only; tracking is the same.")
                hits.take(3).forEach { g ->
                    val first = g.boxes.first()
                    append(" f${g.frame}: ${g.boxes.size} [${first.x1},${first.y1},${first.x2},${first.y2}]")
                }
            }
            ActionResult.ok(
                summary,
                produced = listOfNotNull(
                    sheetPath?.let { Produced("segmentation", "$phrase (${hits.size} frames)".take(80), it) },
                    Produced("file", jsonFile.name, jsonFile.absolutePath),
                ),
            )
        }

    private fun resolveVideoFile(path: String): File {
        val t = path.trim()
        appPrivateFile(context.filesDir.canonicalPath, context.cacheDir.canonicalPath, t)
            ?.takeIf { it.exists() }?.let { return it }
        return when (val r = resolver.resolveForRead(t)) {
            is ToolFiles.ResolveResult.PermissionNeeded ->
                throw IllegalStateException("Need file access to read that video.")
            is ToolFiles.ResolveResult.Error -> throw IllegalStateException(r.message)
            is ToolFiles.ResolveResult.Ok -> r.file
        }.also {
            if (!it.isFile || !it.canRead()) throw IllegalStateException("Couldn't read that video.")
        }
    }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun probeFrame(file: File, timeMs: Long): Boolean {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val bmp = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return false
            if (!bmp.isRecycled()) bmp.recycle()
            return true
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Decodes up to [SamShared.CONTACT_SHEET_FRAMES] of the hit frames at
     * timestamps spread across the clip, draws each frame's boxes scaled from
     * the model's `w`/`h` to the decoded frame, and lays the thumbs out as one
     * PNG. One frame is alive at a time; every bitmap is recycled.
     */
    private fun buildContactSheet(
        file: File,
        durationMs: Long,
        hits: List<SamShared.VideoFrame>,
        dir: File,
        stamp: Long,
    ): String? {
        val picks = SamShared.contactFrameIndexes(hits.size).map { hits[it] }
        if (picks.isEmpty()) return null
        val times = SamShared.contactTimesMs(durationMs, picks.size).ifEmpty { picks.map { 0L } }
        val thumbs = mutableListOf<Bitmap>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            for ((i, group) in picks.withIndex()) {
                val src = runCatching {
                    retriever.getFrameAtTime(times[i] * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull() ?: continue
                try {
                    thumbs.add(drawThumb(src, group.boxes))
                } finally {
                    if (!src.isRecycled()) src.recycle()
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
        if (thumbs.isEmpty()) return null
        val gap = 8
        val sheet = Bitmap.createBitmap(
            thumbs.sumOf { it.width } + gap * (thumbs.size - 1),
            thumbs.maxOf { it.height },
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.BLACK)
        var x = 0
        for (thumb in thumbs) {
            canvas.drawBitmap(thumb, x.toFloat(), ((sheet.height - thumb.height) / 2).toFloat(), null)
            x += thumb.width + gap
            if (!thumb.isRecycled()) thumb.recycle()
        }
        val out = File(dir, "segment-video-$stamp.png")
        val ok = runCatching {
            out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.getOrDefault(false)
        if (!sheet.isRecycled()) sheet.recycle()
        return if (ok && out.exists()) out.absolutePath else null
    }

    private fun drawThumb(src: Bitmap, boxes: List<SamShared.RichBox>): Bitmap {
        val down = 480f / maxOf(src.width, src.height).coerceAtLeast(1)
        val s = if (down < 1f) down else 1f
        val thumb = if (s < 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * s).toInt().coerceAtLeast(1),
                (src.height * s).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            src.copy(Bitmap.Config.ARGB_8888, true)
        }
        val canvas = Canvas(thumb)
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = maxOf(3f, thumb.width / 200f)
        }
        val frameW = boxes.firstOrNull()?.w?.takeIf { it > 0 } ?: src.width
        val frameH = boxes.firstOrNull()?.h?.takeIf { it > 0 } ?: src.height
        val kx = thumb.width / frameW.toFloat()
        val ky = thumb.height / frameH.toFloat()
        for (b in boxes) {
            canvas.drawRect(b.x1 * kx, b.y1 * ky, b.x2 * kx, b.y2 * ky, paint)
        }
        return thumb
    }
}
