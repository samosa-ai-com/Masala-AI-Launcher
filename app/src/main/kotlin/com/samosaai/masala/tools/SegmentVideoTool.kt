package com.samosaai.masala.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.llm.AiSettingsStore
import com.samosaai.masala.tools.sam.MaskCodec
import com.samosaai.masala.tools.sam.MaskDraw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonArray
import java.io.File

/**
 * SAM 3.1 video segmentation: sends a video file + a short noun phrase to
 * `POST {base}/responses` (`model: "sam-3.1"`, same Bearer key as everything
 * else) and returns an annotated MP4 (each sampled frame's masks/boxes burned
 * in) plus a JSON sidecar with the per-frame boxes.
 *
 * The reply uses the same compact token format as images, with per-frame
 * markers (`<0f>…<|box;…|>…<1f>…`) tracking the object across the frames the
 * API samples. Play-safe: frames come from [MediaMetadataRetriever] and the
 * clip is encoded with platform [MediaCodec] + [MediaMuxer] (AVC in MP4, no
 * audio track) — no ffmpeg, no new permissions, no new dependencies. One
 * decoded bitmap is alive at a time; every bitmap is recycled. When encoding
 * fails, the tool falls back to the old contact-sheet PNG and says so.
 */
class SegmentVideoTool(
    private val context: Context,
    private val settings: AiSettingsStore,
    private val resolver: ToolFiles,
) {
    suspend fun segment(
        path: String,
        prompt: String,
        maxFrames: Int? = null,
        mode: String? = null,
        qualityName: String? = null,
    ): ActionResult =
        withContext(Dispatchers.IO) {
            val phrase = prompt.trim()
            if (phrase.isEmpty()) return@withContext ActionResult.error("Say what to find, e.g. 'red bicycle'.")
            val cfg = settings.imageConfig()
                ?: return@withContext ActionResult.error(
                    "Segmentation isn't set up yet. Pick a provider in Settings → Masala AI → Models & keys.",
                )
            val hint = SamShared.clampMaxFrames(maxFrames)
            val quality = SamShared.VideoQuality.parse(qualityName)
            val file = runCatching { resolveVideoFile(path) }.getOrElse {
                return@withContext ActionResult.error(it.message ?: "Couldn't read that video.")
            }
            SamShared.checkVideoSize(file.length())?.let { return@withContext ActionResult.error(it) }
            // One probe frame first, so an unreadable video fails before the network call.
            val durationMs = readDurationMs(file)
            // A long clip is minutes of work at one frame at a time: say so before starting.
            SamShared.clipTooLong(durationMs)?.let { return@withContext ActionResult.error(it) }
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
            val recordFrames = SamShared.parseVideoRecords(text)
            val maskTotal = recordFrames.sumOf { it.records.size }
            // The annotated clip is the result; the contact sheet survives only
            // as the fallback when encoding fails (it was a stand-in).
            var videoPath: String? = null
            var stats: OverlayStats? = null
            var encodeNote: String? = null
            if (recordFrames.isNotEmpty()) {
                runCatching { encodeOverlay(file, durationMs, recordFrames, dir, stamp, quality) }
                    .onSuccess { (path, s) -> videoPath = path; stats = s }
                    .onFailure { encodeNote = it.message?.take(160) }
            }
            val sheetPath = if (videoPath == null) {
                runCatching { buildContactSheet(file, durationMs, hits, dir, stamp) }.getOrNull()
            } else {
                null
            }
            val totalBoxes = hits.sumOf { it.boxes.size }
            val summary = buildString {
                append("Tracked '$phrase' in ${hits.size} of ${groups.size} sampled frames ($totalBoxes boxes, $maskTotal masks).")
                append(" Note: max_frames is a hint — the API samples its own frames (asked $hint, got ${groups.size}).")
                if (!mode.isNullOrBlank()) append(" Mode '$mode' is recorded only; tracking is the same.")
                val s = stats
                if (s != null && videoPath != null) {
                    val seconds = s.frames / s.fps.toFloat()
                    append(
                        " Annotated clip: ${s.frames} frames at ${s.fps} fps, ${s.width}x${s.height} MP4 " +
                            "(~%.1f s, ${quality.wire} quality, ~%d Mbps)".format(
                                seconds, s.width * s.height * quality.bitrateFactor / 1_000_000,
                            ),
                    )
                    if (s.skipped > 0) append(" (${s.skipped} sampled frame${if (s.skipped == 1) "" else "s"} wouldn't decode and were skipped)")
                    append(".")
                } else if (sheetPath != null) {
                    append(" Video encoding failed (${encodeNote ?: "no frames with detections"}) — contact sheet shown instead.")
                }
                hits.take(3).forEach { g ->
                    val first = g.boxes.first()
                    append(" f${g.frame}: ${g.boxes.size} [${first.x1},${first.y1},${first.x2},${first.y2}]")
                }
            }
            ActionResult.ok(
                summary,
                produced = listOfNotNull(
                    videoPath?.let { Produced("video", "$phrase — annotated clip".take(80), it) },
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

    private data class OverlayStats(
        val frames: Int,
        val skipped: Int,
        val fps: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * Encodes the annotated clip with platform [MediaCodec] (AVC) + [MediaMuxer]
     * (MP4, no audio track) — Media3's Transformer edits existing clips and
     * offers no bitmap-frame encoder, so the platform pair is the dependency-free
     * path. Returns the output path with its stats; throws on any failure (the
     * caller falls back to the contact sheet) and deletes the partial file.
     *
     * Streaming: one source frame is decoded, composed, converted and fed at a
     * time; every bitmap is recycled before the next frame is read. Cancellable
     * via [yield] between frames; runs on Dispatchers.IO.
     */
    private suspend fun encodeOverlay(
        file: File,
        durationMs: Long,
        all: List<SamShared.VideoRecordFrame>,
        dir: File,
        stamp: Long,
        quality: SamShared.VideoQuality,
    ): Pair<String, OverlayStats> {
        require(all.isNotEmpty()) { "no frames with detections to encode" }
        val out = File(dir, "segment-video-$stamp.mp4")
        val retriever = MediaMetadataRetriever()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxStopped = false
        try {
            retriever.setDataSource(file.absolutePath)
            val probe = retriever.getFrameAtTime(
                sourceTimeUs(0, all.size, durationMs),
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: throw IllegalStateException("couldn't decode a source frame")
            val (outW, outH) = SamShared.overlaySize(probe.width, probe.height, quality.maxSide)
            if (!probe.isRecycled()) probe.recycle()
            // A fixed grid at a bounded rate: the API may return hundreds of frames, and the
            // clip must still play at its own pace (see SamShared.renderSlots).
            val fps = SamShared.renderFps(durationMs, quality)
            val slots = SamShared.renderSlots(all, durationMs, fps, quality)
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            // Name and format from the same probe: creating by mime can pick a different encoder
            // that doesn't accept the format we configured.
            val (codecName, color) = pickEncoder(mime)
            val format = MediaFormat.createVideoFormat(mime, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    (outW * outH * quality.bitrateFactor).coerceIn(400_000, 20_000_000),
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder = MediaCodec.createByCodecName(codecName).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val enc = encoder
            val mux = muxer
            val semiPlanar = color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            var track = -1
            var muxStarted = false
            val info = MediaCodec.BufferInfo()
            // A codec that stops answering after EOS must not hang the tool: the drain is bounded
            // and cancellation is honoured, so the contact-sheet fallback still runs.
            val drainDeadline = 15_000L
            fun drain(eos: Boolean) {
                val startedAt = System.currentTimeMillis()
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
                    if (eos && System.currentTimeMillis() - startedAt > drainDeadline) {
                        throw IllegalStateException("encoder stopped answering after end of stream")
                    }
                    when (val oi = enc.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> if (eos) continue else return
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxStarted) throw IllegalStateException("encoder format changed twice")
                            track = mux.addTrack(enc.outputFormat)
                            mux.start()
                            muxStarted = true
                        }
                        else -> {
                            if (oi < 0) continue
                            val ob = enc.getOutputBuffer(oi)
                            if (ob != null) {
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                                if (info.size > 0) {
                                    if (!muxStarted) throw IllegalStateException("encoder emitted before muxer start")
                                    ob.position(info.offset)
                                    ob.limit(info.offset + info.size)
                                    mux.writeSampleData(track, ob, info)
                                }
                            }
                            enc.releaseOutputBuffer(oi, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }
            var encoded = 0
            var skipped = 0
            val slotCount = slots.size
            if (slotCount == 0) throw IllegalStateException("no frames to render")
            for ((slot, rec) in slots.withIndex()) {
                // Cancellation point: a few dozen frames now, not one per API sample.
                yield()
                val slotTimeUs = slot * 1_000_000L / fps
                val src = retriever.getFrameAtTime(
                    slotTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )
                if (src == null) {
                    skipped++
                    continue
                }
                try {
                    // No detection on this slot: the real frame still goes in, unannotated.
                    val composed = rec?.let { composeFrame(src, it, outW, outH) }
                        ?: scaleFrame(src, outW, outH)
                    val pixels = IntArray(outW * outH)
                    try {
                        composed.getPixels(pixels, 0, outW, 0, 0, outW, outH)
                    } finally {
                        if (!composed.isRecycled()) composed.recycle()
                    }
                    val yuv = argbToYuv420(pixels, outW, outH, semiPlanar)
                    var fed = false
                    var tries = 0
                    while (!fed && tries++ < 3) {
                        val inIdx = enc.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val ib = enc.getInputBuffer(inIdx)
                                ?: throw IllegalStateException("encoder input buffer gone")
                            ib.clear()
                            ib.put(yuv)
                            // The slot's own timestamp, so the clip keeps its real duration.
                            enc.queueInputBuffer(inIdx, 0, yuv.size, slotTimeUs, 0)
                            fed = true
                            break
                        }
                        drain(false)
                    }
                    if (!fed) throw IllegalStateException("encoder stalled")
                    drain(false)
                    encoded++
                } finally {
                    if (!src.isRecycled()) src.recycle()
                }
            }
            if (encoded == 0) throw IllegalStateException("no sampled frames would decode")
            var eosQueued = false
            var eosTries = 0
            while (!eosQueued && eosTries++ < 30) {
                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    enc.queueInputBuffer(
                        inIdx, 0, 0, slotCount * 1_000_000L / fps,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    eosQueued = true
                    break
                }
                // No free input yet: keep the output moving, or the encoder can never free one.
                drain(false)
            }
            if (!eosQueued) throw IllegalStateException("encoder stalled at end of stream")
            drain(true)
            // Finalize before trusting the file: a muxer that fails to stop leaves an
            // unplayable MP4 behind, and that must reach the fallback rather than be returned.
            if (!muxStarted) throw IllegalStateException("encoder never produced a track")
            mux.stop()
            muxStopped = true
            if (!out.exists() || out.length() == 0L) throw IllegalStateException("encoder wrote nothing")
            return Pair(out.absolutePath, OverlayStats(encoded, skipped, fps, outW, outH))
        } catch (e: Exception) {
            runCatching { out.delete() }
            throw e
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            // Only when the success path did not already stop it (a second stop throws).
            if (!muxStopped) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { retriever.release() }
        }
    }

    /**
     * Source timestamp for the API frame at [pos] of [apiCount]: the API samples
     * evenly, so each frame sits at its segment centre; unknown durations fall
     * back to 1 s apart (the retriever clamps to the nearest sync frame anyway).
     */
    private fun sourceTimeUs(pos: Int, apiCount: Int, durationMs: Long): Long =
        if (durationMs > 0 && apiCount > 0) {
            SamShared.videoFrameTimesMs(apiCount, durationMs).getOrNull(pos)?.times(1000)
                ?: (pos + 1) * 1_000_000L
        } else {
            (pos + 1) * 1_000_000L
        }

    /** Picks an explicit YUV420 input color both the encoder and we can write. */
    /** The encoder to use and a colour format it accepts, chosen together (see the caller). */
    private fun pickEncoder(mime: String): Pair<String, Int> {
        val name = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .findEncoderForFormat(MediaFormat.createVideoFormat(mime, 720, 720))
        }.getOrNull() ?: throw IllegalStateException("no usable $mime encoder on this device")
        val probe = runCatching { MediaCodec.createByCodecName(name) }.getOrNull()
            ?: throw IllegalStateException("no usable $mime encoder on this device")
        try {
            val formats = probe.codecInfo.getCapabilitiesForType(mime).colorFormats.toList()
            val color = when {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in formats ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else -> throw IllegalStateException("encoder has no YUV420 input ($formats)")
            }
            return name to color
        } finally {
            runCatching { probe.release() }
        }
    }

    /**
     * Composes one output frame: the source frame scaled to the output size
     * with each record's mask filled in its per-object hue (alpha 110) plus a
     * 2 px edge, over a faint box — the still overlay's visual language.
     * Records whose mask won't decode, and box-only detections, fall back to a
     * red box. The caller recycles [src]; the returned bitmap is mutable and
     * owned by the caller.
     */
    /** The source frame at the output size, mutable ARGB, with nothing drawn on it. */
    private fun scaleFrame(src: Bitmap, outW: Int, outH: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, outW, outH, true)
        if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) return scaled
        val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("couldn't copy a video frame")
        if (!scaled.isRecycled()) scaled.recycle()
        return copy
    }

    private fun composeFrame(
        src: Bitmap,
        rec: SamShared.VideoRecordFrame,
        outW: Int,
        outH: Int,
    ): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, outW, outH, true)
        val work = if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) {
            scaled
        } else {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("couldn't copy a video frame")
            if (!scaled.isRecycled()) scaled.recycle()
            copy
        }
        val canvas = Canvas(work)
        val faintBox = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, outW / 400f)
            alpha = 48
        }
        val fallbackBox = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = maxOf(3f, outW / 200f)
        }
        rec.records.forEachIndexed { i, r ->
            val s = SamShared.scaleBox(r.box, outW, outH)
            val left = s[0].coerceIn(0f, outW.toFloat())
            val top = s[1].coerceIn(0f, outH.toFloat())
            val right = s[2].coerceIn(0f, outW.toFloat())
            val bottom = s[3].coerceIn(0f, outH.toFloat())
            if (right <= left || bottom <= top) return@forEachIndexed
            val raster = runCatching {
                MaskCodec.decodeMask(r.maskHeight, r.maskWidth, r.maskPayload)
            }.getOrNull()
            if (raster == null || raster.size != r.maskHeight * r.maskWidth) {
                canvas.drawRect(left, top, right, bottom, fallbackBox)
                return@forEachIndexed
            }
            canvas.drawRect(left, top, right, bottom, faintBox)
            val dst = RectF(left, top, right, bottom)
            MaskDraw.fillOn(canvas, raster, r.maskWidth, r.maskHeight, dst, MaskDraw.fillFor(i))
            MaskDraw.edgeOn(canvas, raster, r.maskWidth, r.maskHeight, dst, MaskDraw.edgeFor(i), 2f)
        }
        // Box-only detections have no record; draw the ones not already covered.
        val recordBoxes = rec.records.map { it.box }.toSet()
        for (b in rec.boxes) {
            if (b in recordBoxes) continue
            val s = SamShared.scaleBox(b, outW, outH)
            canvas.drawRect(
                s[0].coerceIn(0f, outW.toFloat()),
                s[1].coerceIn(0f, outH.toFloat()),
                s[2].coerceIn(0f, outW.toFloat()),
                s[3].coerceIn(0f, outH.toFloat()),
                fallbackBox,
            )
        }
        return work
    }

    /**
     * BT.601 ARGB → YUV420 for the encoder input: full-resolution Y plus
     * quarter-resolution chroma (top-left sample of each 2×2 block), laid out
     * interleaved (NV12, semi-planar) or planar (I420) per the encoder's color.
     */
    private fun argbToYuv420(pixels: IntArray, w: Int, h: Int, semiPlanar: Boolean): ByteArray {
        val ySize = w * h
        val out = ByteArray(ySize + ySize / 2)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = px shr 16 and 0xFF
            val g = px shr 8 and 0xFF
            val b = px and 0xFF
            out[i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
        }
        if (semiPlanar) {
            var uv = ySize
            for (j in 0 until h step 2) {
                for (i in 0 until w step 2) {
                    val px = pixels[j * w + i]
                    val r = px shr 16 and 0xFF
                    val g = px shr 8 and 0xFF
                    val b = px and 0xFF
                    out[uv++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    out[uv++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                }
            }
        } else {
            var u = ySize
            var v = ySize + ySize / 4
            for (j in 0 until h step 2) {
                for (i in 0 until w step 2) {
                    val px = pixels[j * w + i]
                    val r = px shr 16 and 0xFF
                    val g = px shr 8 and 0xFF
                    val b = px and 0xFF
                    out[u++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    out[v++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                }
            }
        }
        return out
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
