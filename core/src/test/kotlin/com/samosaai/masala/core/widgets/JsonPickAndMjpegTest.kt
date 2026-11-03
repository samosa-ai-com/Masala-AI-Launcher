package com.samosaai.masala.core.widgets

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonPickAndMjpegTest {
    private val doc = Json.parseToJsonElement(
        """{"data":{"price":42.5,"name":"BTC","tags":["a","b","c"],"odd key":{"x":1}},"list":[{"v":1},{"v":2}]}""",
    )

    @Test
    fun `picks values by path`() {
        assertEquals("42.5", JsonPick.display(JsonPick.pick(doc, "$.data.price")))
        assertEquals("BTC", JsonPick.display(JsonPick.pick(doc, "$['data'][\"name\"]")))
        assertEquals("c", JsonPick.display(JsonPick.pick(doc, "$.data.tags[-1]")))
        assertEquals("2", JsonPick.display(JsonPick.pick(doc, "$.list[1].v")))
        assertEquals("1", JsonPick.display(JsonPick.pick(doc, "$.data['odd key'].x")))
        assertEquals("""{"v":1}""", JsonPick.display(JsonPick.pick(doc, "$.list[0]")))
        assertEquals(doc, JsonPick.pick(doc, "$"))
    }

    @Test
    fun `missing values are null, bad paths are refused`() {
        assertNull(JsonPick.pick(doc, "$.data.nope"))
        assertNull(JsonPick.pick(doc, "$.list[5]"))
        assertNull(JsonPick.pick(doc, "$.data.price.deeper"))
        for (bad in listOf("data.price", "$..price", "$.list[*]", "$[", "$.", "$.a[?(@.v)]", "$[x]")) {
            assertFalse(JsonPick.isValid(bad), bad)
        }
        assertFailsWith<IllegalArgumentException> { JsonPick.compile("$" + ".a".repeat(17)) }
        assertTrue(JsonPick.isValid("$.a_b-c[0]"))
    }

    private fun jpeg(n: Int, payload: Int = 50): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(payload) { (n + it % 7).toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun part(boundary: String, body: ByteArray, withLength: Boolean): ByteArray {
        val headers = buildString {
            append("--$boundary\r\nContent-Type: image/jpeg\r\n")
            if (withLength) append("Content-Length: ${body.size}\r\n")
            append("\r\n")
        }
        return headers.toByteArray(Charsets.ISO_8859_1) + body + "\r\n".toByteArray()
    }

    /** Feeds [stream] in [chunk]-byte pieces, like a socket would. */
    private fun frames(parser: MjpegParser, stream: ByteArray, chunk: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < stream.size) {
            val n = minOf(chunk, stream.size - i)
            out += parser.feed(stream, i, n)
            i += n
        }
        return out
    }

    @Test
    fun `splits multipart frames with and without content length, in any chunking`() {
        val bodies = (1..4).map { jpeg(it) }
        for (withLength in listOf(true, false)) {
            val stream = bodies.fold(ByteArray(0)) { acc, b -> acc + part("frame", b, withLength) } +
                "--frame\r\n".toByteArray() // the next part's start ends the last one when there's no length
            for (chunk in listOf(1, 7, 64, 4096)) {
                val got = frames(MjpegParser("frame"), stream, chunk)
                assertEquals(4, got.size, "length=$withLength chunk=$chunk")
                got.zip(bodies).forEach { (a, b) -> assertContentEquals(b, a) }
            }
        }
    }

    @Test
    fun `boundary forms and the content type`() {
        assertEquals("myboundary", MjpegParser.boundaryOf("multipart/x-mixed-replace; boundary=\"myboundary\""))
        assertEquals("--b", MjpegParser.boundaryOf("multipart/x-mixed-replace;boundary=--b"))
        assertNull(MjpegParser.boundaryOf("image/jpeg"))
        // A boundary parameter that already has the leading dashes still matches.
        val stream = part("b", jpeg(1), withLength = true)
        assertEquals(1, MjpegParser("--b").feed(stream).size)
    }

    @Test
    fun `without a boundary frames are cut at jpeg markers, counting a thumbnail`() {
        val thumb = jpeg(9, payload = 5)
        val withThumb = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2) + thumb + byteArrayOf(3, 0xFF.toByte(), 0xD9.toByte())
        val stream = "junk".toByteArray() + withThumb + "--x\r\n\r\n".toByteArray() + jpeg(2)
        val got = frames(MjpegParser(null), stream, 5)
        assertEquals(2, got.size)
        assertContentEquals(withThumb, got[0])
        assertContentEquals(jpeg(2), got[1])
    }

    @Test
    fun `a split start marker still opens the frame`() {
        val parser = MjpegParser(null)
        val frame = jpeg(3, payload = 20)
        assertTrue(parser.feed(byteArrayOf(0xFF.toByte())).isEmpty())
        val got = parser.feed(byteArrayOf(0xD8.toByte()) + frame.copyOfRange(2, frame.size))
        assertEquals(1, got.size)
        assertContentEquals(frame, got[0])
    }

    @Test
    fun `junk before a frame is skipped, then the frame completes`() {
        val parser = MjpegParser(null)
        val junk = ByteArray(1000) { 0x41 }
        val frame = jpeg(4, payload = 10)
        var i = 0
        while (i < junk.size) {
            val n = minOf(7, junk.size - i)
            assertTrue(parser.feed(junk, i, n).isEmpty())
            i += n
        }
        val got = parser.feed(frame)
        assertEquals(1, got.size)
        assertContentEquals(frame, got[0])
    }

    @Test
    fun `oversized and non-jpeg parts are dropped`() {
        val parser = MjpegParser("f", maxFrameBytes = 100)
        val big = jpeg(1, payload = 500)
        val text = "hello".toByteArray()
        val stream = part("f", big, true) + part("f", text, true) + part("f", jpeg(3), true) + "--f\r\n".toByteArray()
        val got = frames(parser, stream, 16)
        assertEquals(1, got.size)
        assertContentEquals(jpeg(3), got[0])
        assertEquals(2, parser.dropped)
    }
}
