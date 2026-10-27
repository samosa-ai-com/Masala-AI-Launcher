package com.samosaai.masala.core.tool

/**
 * From the private prototype's `FileResolver.detectMime` and the `is*Mime` helpers: a file's type from its first
 * bytes, so a `.dat` that is really a PNG still reads as an image. Moved out of the resolver, which is
 * now [FileAccess].
 */
object FileMime {
    /** A magic-byte signature: minimum file size, required prefix bytes, resulting MIME type. */
    private class Signature(val minSize: Int, val prefix: ByteArray, val mime: String)

    private val signatures = listOf(
        Signature(8, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png"),
        Signature(3, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg"),
        Signature(3, byteArrayOf(0x47, 0x49, 0x46), "image/gif"),
        Signature(2, byteArrayOf(0x42, 0x4D), "image/bmp"),
        Signature(5, byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D), "application/pdf"),
        Signature(4, byteArrayOf(0x50, 0x4B, 0x03, 0x04), "application/zip"),
        Signature(28, byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A), "application/x-rar-compressed"),
        Signature(3, byteArrayOf(0x1F, 0x8B.toByte(), 0x08), "application/gzip"),
        Signature(4, byteArrayOf(0x2E, 0x73, 0x6E, 0x64), "audio/basic"),
        Signature(4, byteArrayOf(0x4D, 0x54, 0x68, 0x64), "audio/midi"),
        Signature(4, byteArrayOf(0x66, 0x74, 0x79, 0x70), "video/mp4"),
    )

    fun detect(bytes: ByteArray): String? {
        // webp needs a two-part check: RIFF header plus WEBP tag at offset 8.
        if (bytes.size >= 12 && startsWith(bytes, byteArrayOf(0x52, 0x49, 0x46, 0x46)) &&
            startsWith(bytes.copyOfRange(8, 12), byteArrayOf(0x57, 0x45, 0x42, 0x50))
        ) {
            return "image/webp"
        }
        return signatures.firstOrNull { bytes.size >= it.minSize && startsWith(bytes, it.prefix) }?.mime
    }

    fun isImage(mime: String): Boolean = mime.startsWith("image/")

    fun isArchive(mime: String): Boolean =
        mime == "application/zip" || mime == "application/x-rar-compressed" || mime == "application/gzip"

    fun isDocument(mime: String): Boolean = mime == "application/pdf"

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i]) return false
        }
        return true
    }
}
