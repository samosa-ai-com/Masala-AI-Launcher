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
    const val MAX_VIDEO_BYTES = 20L * 1024 * 1024

    const val DEFAULT_MAX_FRAMES = 8
    const val MAX_FRAMES_CAP = 16

    /** Contact-sheet preview decodes this many returned frames at most. */
    const val CONTACT_SHEET_FRAMES = 3

    /** One box as the model reports it; [w]/[h] are the frame's pixel size (0 when absent). */
    data class RichBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val w: Int, val h: Int)

    /** One `<Nf>` group of the video wire format: frame index plus its boxes. */
    data class VideoFrame(val frame: Int, val boxes: List<RichBox>)

    private val BOX_RE = Regex("<\\|box;x1=(\\d+);y1=(\\d+);x2=(\\d+);y2=(\\d+);(?:w=(\\d+);h=(\\d+);?)?")
    private val FRAME_RE = Regex("<(\\d+)f>")
    private val MASK_RE = Regex("<\\|mask;")

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

    /** `max_frames` is a hint: clamped to 1..16, default 8. */
    fun clampMaxFrames(v: Int?): Int = (v ?: DEFAULT_MAX_FRAMES).coerceIn(1, MAX_FRAMES_CAP)

    /** Model-facing refusal when the file is too big to inline, else null. */
    fun checkVideoSize(bytes: Long): String? =
        if (bytes > MAX_VIDEO_BYTES) {
            val mb = bytes / (1024.0 * 1024.0)
            "That video is too large (%.1f MB). Send one under ~20 MB, or trim it with media_edit first.".format(mb)
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
