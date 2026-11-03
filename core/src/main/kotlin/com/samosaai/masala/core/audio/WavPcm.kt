package com.samosaai.masala.core.audio

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The little WAV handling the meeting pipeline needs: read the 16 kHz mono 16-bit file the transcriber
 * is about to upload, join it with the previous window's tail (the overlap), and write it back. No
 * Android, no ffmpeg; a RIFF parser narrow enough to be understood and wide enough for files written
 * by this app and by other tools.
 */
object WavPcm {

    data class Audio(val samples: ShortArray, val sampleRate: Int) {
        val durationMs: Long get() = if (sampleRate <= 0) 0 else samples.size * 1000L / sampleRate
    }

    /** Null when the file is not a mono 16-bit PCM WAV this pipeline can upload. */
    fun read(file: File): Audio? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 44) return null
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF") return null
        if (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WAVE") return null
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var format = 0
        var dataFrom = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val body = offset + 8
            if (size < 0 || body + size > bytes.size) {
                // A truncated tail (a recording stopped mid-write) is fine for `data`; not for headers.
                if (id != "data") break
            }
            when (id) {
                "fmt " -> {
                    if (size >= 16) {
                        val buffer = ByteBuffer.wrap(bytes, body, minOf(size, 40)).order(ByteOrder.LITTLE_ENDIAN)
                        format = buffer.short.toInt() and 0xFFFF
                        channels = buffer.short.toInt() and 0xFFFF
                        sampleRate = buffer.int
                        buffer.int // byte rate
                        buffer.short // block align
                        bits = buffer.short.toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    dataFrom = body
                    dataSize = minOf(size, bytes.size - body)
                }
            }
            offset = body + size + (size and 1)
        }
        // 1 = PCM, 0xFFFE = WAVE_FORMAT_EXTENSIBLE (PCM inside); both are 16-bit little-endian here.
        if (dataFrom < 0 || sampleRate <= 0) return null
        if (format != 1 && format != 0xFFFE) return null
        if (channels != 1 || bits != 16) return null
        val count = dataSize / 2
        val samples = ShortArray(count)
        val buffer = ByteBuffer.wrap(bytes, dataFrom, count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().get(samples)
        return Audio(samples, sampleRate)
    }

    fun write(file: File, audio: Audio) {
        file.parentFile?.mkdirs()
        val dataSize = audio.samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(audio.sampleRate)
        header.putInt(audio.sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        file.outputStream().buffered().use { out ->
            out.write(header.array())
            val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            pcm.asShortBuffer().put(audio.samples)
            out.write(pcm.array())
        }
    }

    /**
     * The previous window's tail in front of this window: the provenance overlap that lets speaker
     * labels be matched. [tailMs] of the previous file, or all of it when it is shorter.
     */
    fun withOverlap(previous: Audio?, current: Audio, tailMs: Long): Audio {
        if (previous == null || tailMs <= 0 || previous.sampleRate != current.sampleRate) return current
        val tailCount = (tailMs * previous.sampleRate / 1000).toInt().coerceAtLeast(0)
        val tail = (previous.samples.size - tailCount).coerceAtLeast(0)
        val head = previous.samples.copyOfRange(tail, previous.samples.size)
        if (head.isEmpty()) return current
        val joined = ShortArray(head.size + current.samples.size)
        head.copyInto(joined, 0)
        current.samples.copyInto(joined, head.size)
        return Audio(joined, current.sampleRate)
    }

    /** Reads a file and fails with a message the caller can show instead of a silent null. */
    fun readOrThrow(file: File): Audio =
        read(file) ?: throw IOException("'${file.name}' is not a mono 16-bit WAV")
}
