package com.samosaai.masala.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Bits of the SAM 3.1 Responses-API call shared by `segment_image`
 * ([SamSegmentTool]) and `segment_video` ([SegmentVideoTool]).
 *
 * Deliberately free of Android imports so the pure parts (wire parsing,
 * frame-time math, size/mime helpers) run as plain JVM unit tests.
 */
internal object SamShared {
    const val SAM_MODEL = "sam-3.1"

    /** Refused above this: the whole file is base64'd into one request body. */
    /**
     * Videos are inlined as base64 in the request, which costs roughly three copies in memory
     * (bytes, encoded string, JSON body). 12 MB keeps the peak around 50 MB on a phone heap,
     * still enough for a short clip; longer ones are refused with advice to trim.
     */
    const val MAX_VIDEO_BYTES = 12L * 1024 * 1024

    const val DEFAULT_MAX_FRAMES = 8
    const val MAX_FRAMES_CAP = 16

    /** Contact-sheet preview decodes this many returned frames at most. */
    const val CONTACT_SHEET_FRAMES = 3

    /** Annotated-clip encoder bounds: long side capped, fps clamped to this range. */
    const val OVERLAY_MAX_SIDE = 720
    const val MIN_OVERLAY_FPS = 5
    const val MAX_OVERLAY_FPS = 15

    /** One box as the model reports it; [w]/[h] are the frame's pixel size (0 when absent). */
    data class RichBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val w: Int, val h: Int)

    /** One `<Nf>` group of the video wire format: frame index plus its boxes. */
    data class VideoFrame(val frame: Int, val boxes: List<RichBox>)

    /**
     * One `<Nf>` group with everything that frame reported: full mask records
     * (for the annotated clip) plus all boxes (box-only detections have no
     * record, and the JSON sidecar is built from these).
     */
    data class VideoRecordFrame(val frame: Int, val records: List<SamRecord>, val boxes: List<RichBox>) {
        fun hasDetections(): Boolean = records.isNotEmpty() || boxes.isNotEmpty()
    }

    /**
     * One detection record: object id, box (source pixels, [RichBox.w]/[h] are
     * the frame size) and the mask token's own raster size plus marker+payload
     * (`~…` lossless, `!…` one_bit) for [com.samosaai.masala.tools.sam.MaskCodec].
     */
    data class SamRecord(
        val id: Int,
        val box: RichBox,
        val maskHeight: Int,
        val maskWidth: Int,
        val maskPayload: String,
    )

    private val BOX_RE = Regex("<\\|box;x1=(\\d+);y1=(\\d+);x2=(\\d+);y2=(\\d+);(?:w=(\\d+);h=(\\d+);?)?")
    private val FRAME_RE = Regex("<(\\d+)f>")
    private val MASK_RE = Regex("<\\|mask;")
    private val RECORD_RE = Regex(
        "(\\d+)<\\|box;x1=(\\d+);y1=(\\d+);x2=(\\d+);y2=(\\d+);(?:w=(\\d+);h=(\\d+))?[^|]*\\|>" +
            "<\\|mask;[^|]*?;data=(\\d+),(\\d+),([~!][^|]+)\\|>",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun parseRichBoxes(wire: String): List<RichBox> =
        BOX_RE.findAll(wire).map {
            RichBox(
                it.groupValues[1].toInt(),
                it.groupValues[2].toInt(),
                it.groupValues[3].toInt(),
                it.groupValues[4].toInt(),
                it.groupValues[5].toIntOrNull() ?: 0,
                it.groupValues[6].toIntOrNull() ?: 0,
            )
        }.toList()

    /**
     * Splits the video wire format (`<0f>…<|box;…|>…<1f>…`) into per-frame
     * groups. Text without any `<Nf>` marker (e.g. a single-image reply) is
     * one frame 0 when it holds boxes, else no frames.
     */
    fun parseVideoFrames(wire: String): List<VideoFrame> {
        val marks = FRAME_RE.findAll(wire).toList()
        if (marks.isEmpty()) {
            val boxes = parseRichBoxes(wire)
            return if (boxes.isEmpty()) emptyList() else listOf(VideoFrame(0, boxes))
        }
        return marks.mapIndexed { idx, m ->
            val start = m.range.last + 1
            val end = marks.getOrNull(idx + 1)?.range?.first ?: wire.length
            VideoFrame(m.groupValues[1].toInt(), parseRichBoxes(wire.substring(start, end)))
        }
    }

    fun countMasks(wire: String): Int = MASK_RE.findAll(wire).count()

    /**
     * Splits the video wire format into per-frame record groups, mirroring
     * [parseVideoFrames] but keeping each frame's full mask records alongside
     * its boxes. Text without any `<Nf>` marker is one frame 0.
     */
    fun parseVideoRecords(wire: String): List<VideoRecordFrame> {
        val marks = FRAME_RE.findAll(wire).toList()
        if (marks.isEmpty()) {
            val records = parseRecords(wire)
            val boxes = parseRichBoxes(wire)
            return if (records.isEmpty() && boxes.isEmpty()) {
                emptyList()
            } else {
                listOf(VideoRecordFrame(0, records, boxes))
            }
        }
        return marks.mapIndexed { idx, m ->
            val start = m.range.last + 1
            val end = marks.getOrNull(idx + 1)?.range?.first ?: wire.length
            val segment = wire.substring(start, end)
            VideoRecordFrame(m.groupValues[1].toInt(), parseRecords(segment), parseRichBoxes(segment))
        }
    }

    /**
     * Parses `id<|box;…|><|mask;…|>` records in wire order. The base85 payload
     * alphabet excludes `,;<>|` so the mask token ends at the first `|>`.
     */
    fun parseRecords(wire: String): List<SamRecord> =
        RECORD_RE.findAll(wire).map {
            SamRecord(
                id = it.groupValues[1].toInt(),
                box = RichBox(
                    it.groupValues[2].toInt(),
                    it.groupValues[3].toInt(),
                    it.groupValues[4].toInt(),
                    it.groupValues[5].toInt(),
                    it.groupValues[6].toIntOrNull() ?: 0,
                    it.groupValues[7].toIntOrNull() ?: 0,
                ),
                maskHeight = it.groupValues[8].toInt(),
                maskWidth = it.groupValues[9].toInt(),
                maskPayload = it.groupValues[10],
            )
        }.toList()

    /** `max_frames` is a hint: clamped to 1..16, default 8. */
    fun clampMaxFrames(v: Int?): Int = (v ?: DEFAULT_MAX_FRAMES).coerceIn(1, MAX_FRAMES_CAP)

    /** Model-facing refusal when the file is too big to inline, else null. */
    fun checkVideoSize(bytes: Long): String? =
        if (bytes > MAX_VIDEO_BYTES) {
            val mb = bytes / (1024.0 * 1024.0)
            "That video is too large (%.1f MB). Send one under ~12 MB (a short clip), or trim it with media_edit first.".format(mb)
        } else {
            null
        }

    fun videoMimeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            else -> "video/mp4"
        }

    fun encodeBase64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    /**
     * Evenly spread sample times that skip the very start and end (both are
     * often black or transitional): the centres of `count + 1` segments.
     */
    fun contactTimesMs(durationMs: Long, count: Int): List<Long> {
        if (durationMs <= 0 || count <= 0) return emptyList()
        return (0 until count).map { (it + 1) * durationMs / (count + 1) }
    }

    /** Spread indexes into a frame list for the contact sheet: first, middle, last. */
    fun contactFrameIndexes(total: Int, max: Int = CONTACT_SHEET_FRAMES): List<Int> {
        if (total <= 0 || max <= 0) return emptyList()
        val n = minOf(total, max)
        if (n == 1) return listOf(0)
        return (0 until n).map { it * (total - 1) / (n - 1) }.distinct()
    }

    /**
     * One source timestamp per API-sampled frame, spread like [contactTimesMs]:
     * the API samples its frames evenly across the clip, so frame `i` of
     * `frameCount` sits at the centre of segment `i + 1`. Empty when the count
     * or duration is unknown (callers fall back to a fixed stride).
     */
    fun videoFrameTimesMs(frameCount: Int, durationMs: Long): List<Long> =
        contactTimesMs(durationMs, frameCount)

    /** Clips longer than this are refused with advice: annotating one frame at a time is slow. */
    const val MAX_CLIP_SECONDS = 30

    /**
     * How much time the annotated clip is allowed to cost. Rendering is per frame — decode a
     * source frame, decode its masks, compose, encode — so the frame budget, resolution and
     * bitrate all trade quality for minutes. The user picks one; [BALANCED] is the default.
     */
    enum class VideoQuality(
        val wire: String,
        /** Frames the clip may render; the frame rate follows from clip length. */
        val maxFrames: Int,
        /** Long side of the rendered frames. */
        val maxSide: Int,
        val fpsCap: Int,
        /** Bit rate multiplier over width×height (roughly bits per pixel). */
        val bitrateFactor: Int,
    ) {
        /** Small and quick: a 30 s clip renders at 5 fps. */
        FAST("fast", 150, 720, 15, 4),

        /** The default: twice the frames and a sharper picture for about twice the wait. */
        BALANCED("balanced", 300, 960, 30, 5),

        /** Source-like smoothness and full-HD frames; several times slower. */
        BEST("best", 600, 1080, 30, 8);

        companion object {
            /** Unknown or missing means the default, never a failure. */
            fun parse(value: String?): VideoQuality =
                entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: BALANCED
        }
    }

    /** Frames a [quality] render may use; kept for callers that only know the budget. */
    fun maxFramesFor(quality: VideoQuality): Int = quality.maxFrames

    /** Why a clip was refused, or null when it fits the cap. */
    fun clipTooLong(durationMs: Long): String? {
        if (durationMs <= MAX_CLIP_SECONDS * 1000L) return null
        val seconds = (durationMs + 500) / 1000
        return "That clip is ${seconds}s. Annotating is rendered frame by frame, so clips are " +
            "limited to ${MAX_CLIP_SECONDS}s. Trim it with media_edit, or split it into parts and " +
            "segment them one at a time."
    }

    /**
     * Playback rate for the annotated clip: fast enough to keep motion, capped by how many frames
     * [MAX_RENDER_FRAMES] allows over the clip, so a 7 s clip plays at 15 fps and a 30 s one at 5.
     */
    fun renderFps(durationMs: Long, quality: VideoQuality = VideoQuality.BALANCED): Int {
        if (durationMs <= 0) return quality.fpsCap
        val seconds = (durationMs / 1000.0).coerceAtLeast(0.1)
        return (quality.maxFrames / seconds).toInt().coerceIn(MIN_OVERLAY_FPS, quality.fpsCap)
    }

    /**
     * One entry per rendered frame: the API frame nearest that moment, or null when the object
     * was not visible then (the source frame still goes in, unannotated). Rendering a fixed grid
     * over the clip's own timeline is what keeps the result the real video instead of a montage,
     * and keeps the duration right whatever rate the API sampled at.
     */
    fun renderSlots(
        frames: List<VideoRecordFrame>,
        durationMs: Long,
        fps: Int,
        quality: VideoQuality = VideoQuality.BALANCED,
    ): List<VideoRecordFrame?> {
        if (frames.isEmpty() || durationMs <= 0 || fps <= 0) return emptyList()
        val count = ((durationMs * fps + 999) / 1000).toInt().coerceAtMost(quality.maxFrames)
        val times = videoFrameTimesMs(frames.size, durationMs)
        val byFrame = frames.associateBy { it.frame }
        val slotTimes = (0 until count).map { it * 1000L / fps }
        return slotTimes.map { t ->
            val nearest = times.indices.minByOrNull { kotlin.math.abs(times[it] - t) } ?: return@map null
            byFrame[frames[nearest].frame]
        }
    }

    /**
     * Output frame rate for the annotated clip: the API's sampled rate
     * (`apiFrames` over the clip duration), clamped to 5–15 fps so a sparse
     * sample still plays smoothly and a dense one still encodes quickly.
     * Unknown count or duration plays at the minimum.
     */
    fun overlayFps(apiFrames: Int, durationMs: Long): Int {
        if (apiFrames <= 0 || durationMs <= 0) return MIN_OVERLAY_FPS
        val sampled = ((apiFrames * 1000L + durationMs / 2) / durationMs).toInt()
        return sampled.coerceIn(MIN_OVERLAY_FPS, MAX_OVERLAY_FPS)
    }

    /**
     * Output frame size at the source's aspect ratio with the long side capped
     * at [maxSide] (default 720 px). Dimensions are rounded down to even —
     * the AVC encoder requires it — and floored at 2. Unreadable source sizes
     * fall back to a square 720 frame.
     */
    fun overlaySize(srcW: Int, srcH: Int, maxSide: Int = OVERLAY_MAX_SIDE): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return Pair(maxSide, maxSide)
        val s = (maxSide / maxOf(srcW, srcH).toFloat()).coerceAtMost(1f)
        val w = ((srcW * s).toInt().coerceAtLeast(2) / 2) * 2
        val h = ((srcH * s).toInt().coerceAtLeast(2) / 2) * 2
        return Pair(w, h)
    }

    /** Scales one model-space box onto a decoded frame of [dstW]×[dstH]. */
    fun scaleBox(b: RichBox, dstW: Int, dstH: Int): FloatArray {
        val sx = if (b.w > 0) dstW / b.w.toFloat() else 1f
        val sy = if (b.h > 0) dstH / b.h.toFloat() else 1f
        return floatArrayOf(b.x1 * sx, b.y1 * sy, b.x2 * sx, b.y2 * sy)
    }

    /** `[{frame, boxes:[{x1,y1,x2,y2}]}]` for the saved JSON sidecar. */
    fun framesJson(frames: List<VideoFrame>): String = buildString {
        append('[')
        frames.forEachIndexed { fi, g ->
            if (fi > 0) append(',')
            append("{\"frame\":${g.frame},\"boxes\":[")
            g.boxes.forEachIndexed { bi, b ->
                if (bi > 0) append(',')
                append("{\"x1\":${b.x1},\"y1\":${b.y1},\"x2\":${b.x2},\"y2\":${b.y2}}")
            }
            append("]}")
        }
        append(']')
    }

    fun textPart(text: String) = buildJsonObject {
        put("type", "input_text")
        put("text", text)
    }

    fun imagePart(imageUrl: String) = buildJsonObject {
        put("type", "input_image")
        put("image_url", imageUrl)
    }

    fun videoPart(videoUrl: String) = buildJsonObject {
        put("type", "input_video")
        put("video_url", videoUrl)
    }

    /**
     * `POST {base}/responses` with a ready-made message content array; returns
     * the concatenated output text. Throws [IllegalStateException] with a
     * model-facing message on HTTP or shape errors.
     */
    fun postResponses(baseUrl: String, apiKey: String, model: String, content: JsonArray): String {
        val body = buildJsonObject {
            put("model", model)
            put("input", buildJsonArray {
                addJsonObject {
                    put("type", "message")
                    put("role", "user")
                    put("content", content)
                }
            })
            put("stream", false)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/responses")
            .post(body)
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        http.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
                throw IllegalStateException("HTTP ${response.code}: ${(detail ?: text).take(300)}")
            }
            val output = json.parseToJsonElement(text).jsonObject["output"]?.jsonArray
                ?: throw IllegalStateException("The model returned nothing usable.")
            val sb = StringBuilder()
            for (item in output) {
                val parts = item.jsonObject["content"]?.jsonArray ?: continue
                for (part in parts) {
                    part.jsonObject["text"]?.jsonPrimitive?.content?.let { sb.append(it) }
                }
            }
            return sb.toString()
        }
    }
}

internal fun JsonArrayBuilder.addJsonObject(block: JsonObjectBuilder.() -> Unit) {
    add(buildJsonObject(block))
}
