package com.samosaai.masala.core.widgets

/**
 * Splits an MJPEG-over-HTTP body (`multipart/x-mixed-replace`) into JPEG frames. Pure and
 * incremental: [feed] takes whatever the socket returned and gives back the frames it completed.
 *
 * With a [boundary] each part is read by its `Content-Length` header when it has one, otherwise up to
 * the next boundary. Without one (a camera that sends no or a wrong `Content-Type`) frames are cut
 * from JPEG start-of-image to the matching end-of-image marker. Parts that aren't JPEG are skipped;
 * a frame above [maxFrameBytes] is dropped instead of growing the buffer forever.
 */
class MjpegParser(boundary: String?, private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES) {

    private val delimiter: ByteArray? = boundary?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
        ?.let { if (it.startsWith("--")) it else "--$it" }?.toByteArray(Charsets.ISO_8859_1)

    private var buffer = ByteArray(64 * 1024)
    private var size = 0

    /** Frames dropped for being too big or not JPEG; a hint for the error line. */
    var dropped: Int = 0
        private set

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<ByteArray> {
        append(bytes, offset, length)
        val frames = mutableListOf<ByteArray>()
        if (delimiter != null) readParts(delimiter, frames) else readMarkers(frames)
        if (size > maxFrameBytes * 2) { // nothing completed in far too much data: start over
            dropped++
            size = 0
        }
        return frames
    }

    private fun readParts(delimiter: ByteArray, frames: MutableList<ByteArray>) {
        while (true) {
            val d = indexOf(delimiter, 0)
            if (d < 0) {
                // No part starts here: keep only a tail that could be the start of the next boundary.
                if (size >= delimiter.size) consume(size - delimiter.size + 1)
                return
            }
            val headerStart = d + delimiter.size
            val headerEnd = headerEnd(headerStart) ?: run { consume(d); return }
            val headers = String(buffer, headerStart, headerEnd.first - headerStart, Charsets.ISO_8859_1)
            val bodyStart = headerEnd.second
            val length = CONTENT_LENGTH.find(headers)?.groupValues?.get(1)?.toIntOrNull()
            val bodyEnd: Int
            val next: Int
            if (length != null && length >= 0) {
                if (length > maxFrameBytes) {
                    // Skip to the next boundary instead of buffering the whole part.
                    val after = indexOf(delimiter, bodyStart)
                    if (after < 0) { consume(d); return }
                    dropped++
                    consume(after)
                    continue
                }
                if (size < bodyStart + length) { consume(d); return }
                bodyEnd = bodyStart + length
                next = bodyEnd
            } else {
                val after = indexOf(delimiter, bodyStart)
                if (after < 0) { consume(d); return }
                var end = after
                while (end > bodyStart && (buffer[end - 1] == LF || buffer[end - 1] == CR)) end--
                bodyEnd = end
                next = after
            }
            emit(bodyStart, bodyEnd, frames)
            consume(next)
        }
    }

    /** Start-of-image to its end-of-image, counting nested images (an EXIF thumbnail). */
    private fun readMarkers(frames: MutableList<ByteArray>) {
        while (true) {
            var start = -1
            for (i in 0 until size - 1) if (buffer[i] == FF && buffer[i + 1] == SOI) { start = i; break }
            if (start < 0) { if (size > 1) consume(size - 1); return }
            var depth = 0
            var i = start
            var end = -1
            while (i < size - 1) {
                if (buffer[i] == FF) {
                    when (buffer[i + 1]) {
                        SOI -> { depth++; i += 2; continue }
                        EOI -> { depth--; if (depth == 0) { end = i + 2; break }; i += 2; continue }
                    }
                }
                i++
            }
            if (end < 0) { consume(start); return }
            emit(start, end, frames)
            consume(end)
        }
    }

    private fun emit(from: Int, to: Int, frames: MutableList<ByteArray>) {
        val length = to - from
        if (length < 4 || buffer[from] != FF || buffer[from + 1] != SOI || length > maxFrameBytes) {
            dropped++
            return
        }
        frames += buffer.copyOfRange(from, to)
    }

    /** End of the part headers (`\r\n\r\n` or `\n\n`) after [from]: (headers end, body start). */
    private fun headerEnd(from: Int): Pair<Int, Int>? {
        var i = from
        while (i < size) {
            if (buffer[i] == LF) {
                if (i + 1 < size && buffer[i + 1] == LF) return i to i + 2
                if (i + 2 < size && buffer[i + 1] == CR && buffer[i + 2] == LF) return i to i + 3
            }
            i++
        }
        return null
    }

    private fun indexOf(needle: ByteArray, from: Int): Int {
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) if (buffer[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (size + length > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + length))
        System.arraycopy(bytes, offset, buffer, size, length)
        size += length
    }

    private fun consume(count: Int) {
        if (count <= 0) return
        System.arraycopy(buffer, count, buffer, 0, size - count)
        size -= count
    }

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 4 * 1024 * 1024
        private const val FF = 0xFF.toByte()
        private const val SOI = 0xD8.toByte()
        private const val EOI = 0xD9.toByte()
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
        private val CONTENT_LENGTH = Regex("""(?im)^content-length:\s*(\d+)\s*$""")

        /** The `boundary` parameter of a `multipart/x-mixed-replace` content type, or null. */
        fun boundaryOf(contentType: String?): String? = contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }
    }
}
