package com.samosaai.masala.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cuts one decoded WAV into the same five-minute windows a live recording produces, so an imported
 * meeting goes through the ordinary chunk pipeline (one ASR request per window, overlap stitching,
 * retries). Streaming, not loading: an hour at 16 kHz is 115 MB on disk and must never become a
 * 230 MB array on a phone.
 */
object WavSplitter {

    data class Part(val file: File, val startMs: Long, val durationMs: Long)

    private data class Header(
        val sampleRate: Int,
        val dataOffset: Long,
        val dataSize: Long,
    )

    /** Splits [source] into `[namePrefix]-000.wav`, … Returns the parts in order. */
    fun split(source: File, outDir: File, namePrefix: String, windowMs: Long): List<Part> {
        if (windowMs <= 0) return emptyList()
        outDir.mkdirs()
        val header = readHeader(source) ?: return emptyList()
        val bytesPerMs = header.sampleRate * 2.0 / 1000.0
        val windowBytes = (windowMs * bytesPerMs).toLong().coerceAtLeast(2) and -2L
        if (header.dataSize <= 0) return emptyList()
        val parts = mutableListOf<Part>()
        RandomAccessFile(source, "r").use { input ->
            input.seek(header.dataOffset)
            var index = 0
            var written = 0L
            val buffer = ByteArray(64 * 1024)
            while (written < header.dataSize) {
                val bytes = minOf(windowBytes, header.dataSize - written).toInt() and -2
                if (bytes <= 0) break
                val part = File(outDir, "$namePrefix-%03d.wav".format(index))
                part.outputStream().buffered().use { out ->
                    out.write(wavHeader(bytes, header.sampleRate))
                    var left = bytes
                    while (left > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, left))
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        left -= read
                    }
                }
                parts += Part(
                    file = part,
                    startMs = index * windowMs,
                    durationMs = (bytes / 2L) * 1000L / header.sampleRate,
                )
                written += bytes
                index++
            }
        }
        return parts
    }

    /** Mono 16-bit PCM only, which is what the transcriber writes. */
    private fun readHeader(file: File): Header? {
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return null
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var offset = 12L
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0
            var dataOffset = -1L
            var dataSize = 0L
            while (offset + 8 <= raf.length()) {
                raf.seek(offset)
                val id = ByteArray(4)
                raf.readFully(id)
                val size = raf.readIntLe()
                if (size < 0) break
                val body = offset + 8
                when (String(id, Charsets.US_ASCII)) {
                    "fmt " -> {
                        raf.seek(body)
                        val fmt = ByteArray(minOf(size, 16L).toInt())
                        raf.readFully(fmt)
                        val buffer = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        format = buffer.short.toInt() and 0xFFFF
                        channels = buffer.short.toInt() and 0xFFFF
                        sampleRate = buffer.int
                        buffer.int
                        buffer.short
                        bits = buffer.short.toInt() and 0xFFFF
                    }
                    "data" -> {
                        dataOffset = body
                        dataSize = minOf(size, raf.length() - body)
                    }
                }
                offset = body + size + (size and 1L)
            }
            if (dataOffset < 0 || sampleRate <= 0) return null
            if (format != 1 && format != 0xFFFE) return null
            if (channels != 1 || bits != 16) return null
            return Header(sampleRate, dataOffset, dataSize)
        }
    }

    private fun RandomAccessFile.readIntLe(): Long {
        val bytes = ByteArray(4)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
    }

    private fun wavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        return header.array()
    }
}
