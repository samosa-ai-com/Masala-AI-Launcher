package com.samosaai.masala.tools.sam

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Golden vectors generated from the reference Python implementation
 * (`meta_sam_parser`, `PYTHONPATH=/tmp/opencode/msam`):
 * each payload below was produced (one_bit) or decoded (lossless) by the
 * reference, and the expected rasters were cross-checked there with
 * `decode_mask_to_raster` round-trips. Exact byte match required, no tolerance.
 */
class MaskCodecTest {

    // 24x16 circle (r^2 <= 36 around the centre), one_bit, 112 ones.
    private val circlePayload = "!!!!!7Crcs_[HeYLXy%z7:f`0PIl{+b"
    private fun circleExpected(w: Int = 24, h: Int = 16): ByteArray =
        ByteArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val dx = x - (w - 1) / 2.0
            val dy = y - (h - 1) / 2.0
            if (dx * dx + dy * dy <= 36.0) 1 else 0
        }

    // 24x16 filled rectangle x in 4..19, y in 3..12, one_bit, 160 ones.
    private val rectPayload = "!!!!!/CA(&Kn%:td1i_qT"
    private fun rectExpected(w: Int = 24, h: Int = 16): ByteArray =
        ByteArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 4..19 && y in 3..12) 1 else 0
        }

    // 40x30 diagonal split (x > y*40/30), one_bit, 600 ones.
    private val diagPayload = "!!!!!0NhnM!I=[aHtyQd/+A"
    private fun diagExpected(w: Int = 40, h: Int = 30): ByteArray =
        ByteArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x > y * w / h) 1 else 0
        }

    // Real lossless payload from the wire-format doc (27 rows x 60 cols).
    private val losslessPayload = "~!!!!M!0c[0o91w?q1!pIH4pPRVp2B3'7`e.ioeAf6-k/#Xd8%dX9x("

    @Test
    fun `one_bit circle matches reference exactly`() {
        val expected = circleExpected()
        assertEquals(112, expected.count { it == 1.toByte() })
        assertContentEquals(expected, MaskCodec.decodeMask(16, 24, circlePayload))
    }

    @Test
    fun `one_bit rectangle matches reference exactly`() {
        val expected = rectExpected()
        assertEquals(160, expected.count { it == 1.toByte() })
        assertContentEquals(expected, MaskCodec.decodeMask(16, 24, rectPayload))
    }

    @Test
    fun `one_bit diagonal matches reference exactly`() {
        val expected = diagExpected()
        assertEquals(600, expected.count { it == 1.toByte() })
        assertContentEquals(expected, MaskCodec.decodeMask(30, 40, diagPayload))
    }

    @Test
    fun `lossless payload matches reference exactly`() {
        val raster = MaskCodec.decodeMask(27, 60, losslessPayload)
        assertEquals(27 * 60, raster.size)
        assertEquals(1539, raster.count { it == 1.toByte() })
        val sha = MessageDigest.getInstance("SHA-256").digest(raster)
            .joinToString("") { "%02x".format(it) }
        assertEquals("9dce7d753492672c47758e016b99244f87106fb01ed4df2c7af68cd56edc6730", sha)
        fun row(y: Int) = raster.slice(y * 60 until (y + 1) * 60).joinToString("") { it.toString() }
        assertEquals("000001111111111111111111111111111111111111111111111111000000", row(0))
        assertEquals("111111111111111111111111111111111111111111111111111111111110", row(13))
        assertEquals("000000011111111111111111111111111111111111111111111100000000", row(26))
    }

    @Test
    fun `halveGuarded is a zero floor not a bitwise or`() {
        // (4089 >> 1) or 1 in Python is logical-or: 2044, not 2044|1 = 2045.
        assertEquals(2044, halveGuarded(4089))
        assertEquals(1, halveGuarded(1))
        assertEquals(1, halveGuarded(0))
        assertEquals(7, halveGuarded(15))
    }

    @Test
    fun `bad marker and truncated payload fail`() {        assertFailsWith<MaskDecodeException> { MaskCodec.decodeMask(16, 24, "abc") }
        assertFailsWith<MaskDecodeException> { MaskCodec.decodeMask(16, 24, "!") }
        assertFailsWith<MaskDecodeException> { MaskCodec.decodeMask(0, 24, circlePayload) }
    }
}
