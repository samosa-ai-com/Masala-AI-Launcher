// Ported from the Meta SAM segmentation parser (`meta-sam-parser` on PyPI,
// `meta_sam_parser/_mask_codec.py`), Copyright (c) Meta Platforms, Inc. and
// affiliates, under the SAM License (see THIRD_PARTY.md). The decode path is
// a faithful port: custom base85 alphabet, adaptive binary range coder with
// spatial/context modelling, Paeth-style predictors for lossless, and the
// 12-bit neighbourhood model for one_bit. Do not simplify the algorithm;
// validate changes against MaskCodecTest's golden vectors, which must match
// the Python reference byte-for-byte.
package com.samosaai.masala.tools.sam

/** Thrown when a mask payload is malformed, truncated, or non-canonical. */
class MaskDecodeException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Halving rescale with a zero floor. NOTE: the reference writes this as
 * `(x >> 1) or 1`, where `or` is Python's *logical* or — halve, but keep 1
 * when halving would give 0. It is NOT a bitwise-or (which would set the low
 * bit); that misread silently corrupts the adaptive models once any counter
 * rescales. Applies at the three rescale sites below.
 */
internal fun halveGuarded(value: Int): Int {
    val halved = value shr 1
    return if (halved == 0) 1 else halved
}

/** Row-major 0/1 raster decoded from one wire-format mask token. */
object MaskCodec {
    private const val RADIX = 85
    private const val PREFIX_LENGTH = 5
    private const val MASK32 = 0xFFFFFFFFL
    private const val MASK16 = 0xFFFF

    // Range-coder renormalisation bounds and adaptive-model limits/steps.
    // These mirror the reference exactly; see the comments at their use sites.
    private const val TOP = 1 shl 24
    private const val BOTTOM = 1 shl 16
    private const val INCREMENT = 14
    private const val COUNT_LIMIT = 4096
    private const val CONTEXT_COUNT = 1 shl 12
    private const val PADDING = 2
    private const val SYMBOLS = 256
    private const val TOTAL_INDEX = SYMBOLS
    private const val SPATIAL_MODES = 5
    private const val ZERO_INCREMENT = 32
    private const val ZERO_LIMIT = 16384
    private const val ORDER_ONE_INCREMENT = 56
    private const val ORDER_ONE_LIMIT = 8192
    private const val ORDER_TWO_STEP = 14 * 44
    private const val ORDER_TWO_LIMIT = 14 * 3584
    private const val ORDER_ZERO_STEP = 2 * 16
    private const val ORDER_ZERO_LIMIT = 2 * 2048
    private const val ORDER_ZERO_INITIAL = 2
    private const val ORDER_ONE_INITIAL = 1

    // Custom base85 alphabet: printable ASCII 0x21..0x7E minus the wire-format
    // delimiters `" \ , ; < > |`. Built in code-point order like the reference.
    private val ALPHABET: String = buildString {
        val excluded = setOf('"', '\\', ',', ';', '<', '>', '|')
        for (code in 0x21..0x7E) {
            val c = code.toChar()
            if (c !in excluded) append(c)
        }
    }
    /**
     * Ceiling on a mask raster. Masks come back at a fraction of the source size (a few hundred
     * pixels a side); 4 MP is far beyond any real mask and still small enough to allocate safely.
     */
    private const val MAX_MASK_PIXELS = 4_000_000L

    private val CHAR_VALUES: IntArray = IntArray(128) { -1 }.also {
        for (i in ALPHABET.indices) it[ALPHABET[i].code] = i
    }

    /**
     * Decodes one mask token's marker+payload (the `<enc>payload` part after
     * `data=H,W,`) into a row-major [height]×[width] raster of 0/1 bytes.
     * `~` selects the lossless path (grayscale coverage thresholded at 129,
     * as the reference does); `!` selects one_bit (already binary, with a
     * canonical re-encode check).
     */
    fun decodeMask(height: Int, width: Int, markerAndPayload: String): ByteArray {
        if (width <= 0 || height <= 0) {
            throw MaskDecodeException("Mask dimensions must be positive.")
        }
        // The wire carries the raster size, so a malformed response could ask for gigabytes.
        // Masks are small (a few hundred pixels a side); anything past this is not a mask.
        val area = height.toLong() * width.toLong()
        if (area > MAX_MASK_PIXELS) {
            throw MaskDecodeException("Mask is larger than ${MAX_MASK_PIXELS / 1_000_000} MPixels.")
        }
        if (area > Int.MAX_VALUE) throw MaskDecodeException("Mask dimensions overflow a raster.")
        if (markerAndPayload.isEmpty()) throw MaskDecodeException("Mask payload is empty.")
        return try {
            when (markerAndPayload[0]) {
                '~' -> {
                    val coverage = decodeLosslessRaster(markerAndPayload, width, height)
                    ByteArray(coverage.size) { i ->
                        if ((coverage[i].toInt() and 0xFF) >= 129) 1 else 0
                    }
                }
                '!' -> {
                    val raster = decodeOneBitRaster(markerAndPayload, width, height)
                    // Strictness from the reference: reject non-canonical payloads.
                    if (encodeOneBitRaster(raster, width, height) != markerAndPayload) {
                        throw MaskDecodeException("Mask payload is not canonical or has invalid finalization.")
                    }
                    raster
                }
                else -> throw MaskDecodeException("Mask payload must start with ~ or !.")
            }
        } catch (e: MaskDecodeException) {
            throw e
        } catch (e: Exception) {
            throw MaskDecodeException("Mask payload could not be decoded.", e)
        }
    }

    // ---- base85 packing ---------------------------------------------------

    private fun digit(c: Char): Int {
        val v = if (c.code < CHAR_VALUES.size) CHAR_VALUES[c.code] else -1
        if (v < 0 || v >= RADIX) throw MaskDecodeException("Mask payload contains an invalid character.")
        return v
    }

    internal fun pack(input: ByteArray): String {
        val length = input.size
        var remaining = length
        val prefix = CharArray(PREFIX_LENGTH)
        for (i in PREFIX_LENGTH - 1 downTo 0) {
            prefix[i] = ALPHABET[remaining % RADIX]
            remaining /= RADIX
        }
        if (remaining != 0) throw MaskDecodeException("Mask payload is too large.")
        val out = StringBuilder().append(prefix)
        var offset = 0
        val full = length - length % 4
        while (offset < full) {
            var value = (((input[offset].toLong() and 0xFF) shl 24) or
                ((input[offset + 1].toLong() and 0xFF) shl 16) or
                ((input[offset + 2].toLong() and 0xFF) shl 8) or
                (input[offset + 3].toLong() and 0xFF))
            val digits = IntArray(5)
            for (i in 4 downTo 0) {
                digits[i] = (value % RADIX).toInt()
                value = (value - digits[i]) / RADIX
            }
            for (d in digits) out.append(ALPHABET[d])
            offset += 4
        }
        val remainder = length - offset
        if (remainder > 0) {
            // Tail: pad with zero bytes, emit remainder+1 digits.
            var value = 0L
            for (i in 0 until 4) {
                value = ((value shl 8) or
                    (if (i < remainder) input[offset + i].toLong() and 0xFF else 0)) and MASK32
            }
            val digits = IntArray(5)
            for (i in 4 downTo 0) {
                digits[i] = (value % RADIX).toInt()
                value = (value - digits[i]) / RADIX
            }
            for (i in 0 until remainder + 1) out.append(ALPHABET[digits[i]])
        }
        return out.toString()
    }

    internal fun unpack(payload: String): ByteArray {
        // Kotlin's String.length is UTF-16 units, matching the reference's
        // JS-string-length accounting (payloads are ASCII in practice).
        if (payload.length < PREFIX_LENGTH) {
            throw MaskDecodeException("Mask payload is missing its length prefix.")
        }
        var length = 0L
        for (c in payload.take(PREFIX_LENGTH)) length = length * RADIX + digit(c)
        val remainder = (length % 4).toInt()
        val expected = PREFIX_LENGTH + length / 4 * 5 + (if (remainder == 0) 0 else remainder + 1)
        if (payload.length.toLong() != expected) {
            throw MaskDecodeException("Mask payload length does not match its prefix.")
        }
        if (length > Int.MAX_VALUE) throw MaskDecodeException("Mask payload length is unsupported.")
        val output = ByteArray(length.toInt())
        var source = PREFIX_LENGTH
        var destination = 0
        val full = (length - remainder).toInt()
        while (destination < full) {
            var value = 0L
            for (i in 0 until 5) value = value * RADIX + digit(payload[source + i])
            if (value > MASK32) throw MaskDecodeException("Mask payload contains an out-of-range group.")
            source += 5
            output[destination] = ((value shr 24) and 0xFF).toByte()
            output[destination + 1] = ((value shr 16) and 0xFF).toByte()
            output[destination + 2] = ((value shr 8) and 0xFF).toByte()
            output[destination + 3] = (value and 0xFF).toByte()
            destination += 4
        }
        if (remainder > 0) {
            // Tail: missing digits decode as RADIX-1 (all padding bits set),
            // then the round-trip check below pins the canonical form.
            var value = 0L
            for (i in 0 until 5) {
                value = value * RADIX + (if (i < remainder + 1) digit(payload[source + i]) else RADIX - 1)
            }
            if (value > MASK32) throw MaskDecodeException("Mask payload contains an out-of-range tail.")
            for (i in 0 until remainder) {
                output[destination + i] = ((value shr (24 - i * 8)) and 0xFF).toByte()
            }
        }
        if (pack(output) != payload) throw MaskDecodeException("Mask payload is not in canonical form.")
        return output
    }

    // ---- adaptive binary range coder --------------------------------------

    internal class RangeDecoder(input: ByteArray) {
        private val input: ByteArray = input
        private var position = 0
        private var low = 0L
        private var range = MASK32
        private var code = 0L
        private var scaled = 0L

        init {
            if (input.size < 4) throw MaskDecodeException("Mask payload is missing its finalization.")
            repeat(4) { code = ((code shl 8) or read().toLong()) and MASK32 }
        }

        /** Returns which frequency bin [code] falls in, saving the scale for [decode]. */
        fun frequency(total: Int): Int {
            if (total <= 0) throw MaskDecodeException("Mask payload has an empty model.")
            scaled = range / total
            if (scaled == 0L) throw MaskDecodeException("Mask payload has invalid coding state.")
            val value = ((code - low) and MASK32) / scaled
            return if (value >= total) total - 1 else value.toInt()
        }

        fun decode(cumulative: Int, frequency: Int) {
            low = (low + scaled * cumulative) and MASK32
            range = scaled * frequency
            // Renormalisation: while the top byte of low..low+range is not
            // stable, shift a byte out (top-byte-stable case) or, when the
            // range has underflowed below BOTTOM, synthesize the range from
            // -low masked to 16 bits (carry-propagation case).
            while (true) {
                if (((low xor ((low + range) and MASK32)) and MASK32) < TOP) {
                    // Top byte stable: emit it.
                } else if (range < BOTTOM) {
                    range = ((0L - low) and MASK32) and (BOTTOM - 1).toLong()
                } else {
                    break
                }
                code = ((code shl 8) or read().toLong()) and MASK32
                low = (low shl 8) and MASK32
                range = (range shl 8) and MASK32
            }
        }

        private fun read(): Int {
            if (position >= input.size) {
                throw MaskDecodeException("Mask payload ended before decoding completed.")
            }
            return input[position++].toInt() and 0xFF
        }
    }

    internal class RangeEncoder {
        private var low = 0L
        private var range = MASK32
        private val output = java.io.ByteArrayOutputStream()

        fun encode(cumulative: Int, frequency: Int, total: Int) {
            val scaled = range / total
            low = (low + scaled * cumulative) and MASK32
            range = scaled * frequency
            while (true) {
                if (((low xor ((low + range) and MASK32)) and MASK32) < TOP) {
                } else if (range < BOTTOM) {
                    range = ((0L - low) and MASK32) and (BOTTOM - 1).toLong()
                } else {
                    break
                }
                output.write(((low shr 24) and 0xFF).toInt())
                low = (low shl 8) and MASK32
                range = (range shl 8) and MASK32
            }
        }

        fun finish(): ByteArray {
            repeat(4) {
                output.write(((low shr 24) and 0xFF).toInt())
                low = (low shl 8) and MASK32
            }
            return output.toByteArray()
        }
    }

    // ---- lossless path (~): predictive residual coding --------------------

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val estimate = left + up - upperLeft
        val ld = kotlin.math.abs(estimate - left)
        val ud = kotlin.math.abs(estimate - up)
        val uld = kotlin.math.abs(estimate - upperLeft)
        if (ld <= ud && ld <= uld) return left
        return if (ud <= uld) up else upperLeft
    }

    /**
     * Spatial predictor for one coverage byte. Mode 0 is Paeth, 1/2 copy
     * up/left, 3 averages, 4 is the gradient-adjusted variant; the mode byte
     * leads the packed stream and picks one for the whole mask.
     */
    private fun predictByte(output: ByteArray, offset: Int, x: Int, y: Int, mode: Int, width: Int): Int {
        if (mode < 0 || mode >= SPATIAL_MODES) {
            throw MaskDecodeException("Lossless mask payload uses an invalid predictor.")
        }
        val left = if (x > 0) output[offset - 1].toInt() and 0xFF else 0
        val up = if (y > 0) output[offset - width].toInt() and 0xFF else 0
        if (mode == 1) return up
        if (mode == 2) return left
        if (mode == 3) return (left + up) shr 1
        val upperLeft = if (x > 0 && y > 0) output[offset - width - 1].toInt() and 0xFF else 0
        if (mode == 0) return paeth(left, up, upperLeft)
        if (upperLeft >= maxOf(left, up)) return minOf(left, up)
        if (upperLeft <= minOf(left, up)) return maxOf(left, up)
        return left + up - upperLeft
    }

    /** Zig-zag decode of the residual around the prediction. */
    private fun unzig(value: Int, prediction: Int): Int {
        val delta = if (value and 1 != 0) -((value + 1) shr 1) else value shr 1
        return (prediction + delta) and 0xFF
    }

    /**
     * 4-bit zero/non-zero neighbourhood context (left, up, upper-left,
     * upper-right; out-of-bounds counts as zero) selecting one of 16
     * zero-ratio models, each tracking zero vs non-zero counts.
     */
    private fun zeroContext(residuals: ByteArray, offset: Int, x: Int, y: Int, width: Int): Int {
        val left = if (x > 0 && residuals[offset - 1].toInt() == 0) 1 else if (x > 0) 0 else 1
        val up = if (y > 0 && residuals[offset - width].toInt() == 0) 1 else if (y > 0) 0 else 1
        val upperLeft = if (x > 0 && y > 0 && residuals[offset - width - 1].toInt() == 0) 1
        else if (x > 0 && y > 0) 0 else 1
        val upperRight = if (y > 0 && x < width - 1 && residuals[offset - width + 1].toInt() == 0) 1
        else if (y > 0 && x < width - 1) 0 else 1
        return left or (up shl 1) or (upperLeft shl 2) or (upperRight shl 3)
    }

    private fun getOrderTwo(countsByContext: HashMap<Int, IntArray>, key: Int): IntArray =
        countsByContext.getOrPut(key) { IntArray(SYMBOLS + 1) }

    /** Raw grayscale coverage raster (0..255); the caller thresholds to 0/1. */
    internal fun decodeLosslessRaster(payload: String, width: Int, height: Int): ByteArray {
        if (!payload.startsWith("~")) throw MaskDecodeException("lossless mask payloads must start with ~.")
        val packed = unpack(payload.substring(1))
        if (packed.size < 5) throw MaskDecodeException("Lossless mask payload is truncated.")
        val selector = packed[0].toInt() and 0xFF
        val decoder = RangeDecoder(packed.copyOfRange(1, packed.size))
        val length = width * height
        val output = ByteArray(length)
        val residuals = ByteArray(length)
        val zeroCounts = IntArray(32) { 1 }
        val orderOne = IntArray(SYMBOLS * SYMBOLS) { ORDER_ONE_INITIAL }
        val orderOneTotals = IntArray(SYMBOLS) { SYMBOLS * ORDER_ONE_INITIAL }
        val orderZero = IntArray(SYMBOLS) { ORDER_ZERO_INITIAL }
        var orderZeroTotal = SYMBOLS * ORDER_ZERO_INITIAL
        val orderTwo = HashMap<Int, IntArray>()
        var previous = 0

        for (offset in 0 until length) {
            val x = offset % width
            val y = offset / width
            // Zero bit: is this residual zero? Context picks the model pair.
            val zeroOffset = zeroContext(residuals, offset, x, y, width) shl 1
            val zero = zeroCounts[zeroOffset]
            val nonzero = zeroCounts[zeroOffset + 1]
            val zeroFrequency = decoder.frequency(zero + nonzero)
            val bit = if (zeroFrequency < zero) 0 else 1
            decoder.decode(if (bit == 0) 0 else zero, if (bit == 0) zero else nonzero)
            zeroCounts[zeroOffset + bit] += ZERO_INCREMENT
            if (zeroCounts[zeroOffset] + zeroCounts[zeroOffset + 1] >= ZERO_LIMIT) {
                zeroCounts[zeroOffset] = halveGuarded(zeroCounts[zeroOffset])
                zeroCounts[zeroOffset + 1] = halveGuarded(zeroCounts[zeroOffset + 1])
            }

            // Non-zero magnitude: blended order-2 (previous, above), order-1
            // (previous), and order-0 models vote per symbol; symbol 0 is
            // excluded because the zero bit already ruled it out.
            var symbol = 0
            if (bit == 1) {
                val above = if (offset >= width) residuals[offset - width].toInt() and 0xFF else 0
                val second = getOrderTwo(orderTwo, (previous shl 8) or above)
                val firstOffset = previous shl 8
                val excludedZero = second[0] + orderOne[firstOffset] + orderZero[0]
                val total = second[TOTAL_INDEX] + orderOneTotals[previous] + orderZeroTotal - excludedZero
                val target = decoder.frequency(total)
                var cumulative = 0
                symbol = 1
                var frequency = second[1] + orderOne[firstOffset + 1] + orderZero[1]
                while (cumulative + frequency <= target) {
                    cumulative += frequency
                    symbol += 1
                    if (symbol >= SYMBOLS) throw MaskDecodeException("Lossless mask payload is malformed.")
                    frequency = second[symbol] + orderOne[firstOffset + symbol] + orderZero[symbol]
                }
                decoder.decode(cumulative, frequency)
                second[symbol] = (second[symbol] + ORDER_TWO_STEP) and MASK16
                second[TOTAL_INDEX] = (second[TOTAL_INDEX] + ORDER_TWO_STEP) and MASK16
                if (second[TOTAL_INDEX] >= ORDER_TWO_LIMIT) {
                    var scaledTotal = 0
                    for (v in 0 until SYMBOLS) {
                        second[v] = second[v] shr 1
                        scaledTotal += second[v]
                    }
                    second[TOTAL_INDEX] = scaledTotal and MASK16
                }
                orderOne[firstOffset + symbol] = (orderOne[firstOffset + symbol] + ORDER_ONE_INCREMENT) and MASK16
                orderOneTotals[previous] += ORDER_ONE_INCREMENT
                if (orderOneTotals[previous] >= ORDER_ONE_LIMIT) {
                    var scaledTotal = 0
                    for (v in 0 until SYMBOLS) {
                        val scaled = halveGuarded(orderOne[firstOffset + v])
                        orderOne[firstOffset + v] = scaled
                        scaledTotal += scaled
                    }
                    orderOneTotals[previous] = scaledTotal
                }
                orderZero[symbol] = (orderZero[symbol] + ORDER_ZERO_STEP) and MASK16
                orderZeroTotal += ORDER_ZERO_STEP
                if (orderZeroTotal >= ORDER_ZERO_LIMIT) {
                    var scaledTotal = 0
                    for (v in 0 until SYMBOLS) {
                        orderZero[v] = orderZero[v] shr 1
                        scaledTotal += orderZero[v]
                    }
                    orderZeroTotal = scaledTotal
                }
            }
            residuals[offset] = symbol.toByte()
            output[offset] = unzig(symbol, predictByte(output, offset, x, y, selector, width)).toByte()
            previous = symbol
        }
        return output
    }

    // ---- one_bit path (!): binary context modelling ------------------------

    /**
     * 12-bit context from two rows of already-decoded neighbours: two left,
     * the five above (prior row ±2), and the five two rows up. Indexes one of
     * 4096 zero/one count pairs in a zero-padded raster.
     */
    private fun contextAt(raster: ByteArray, position: Int, prior: Int, earlier: Int): Int {
        fun b(i: Int): Int = raster[i].toInt() and 0xFF
        return b(position - 1) or (b(position - 2) shl 1) or
            (b(prior - 2) shl 2) or (b(prior - 1) shl 3) or
            (b(prior) shl 4) or (b(prior + 1) shl 5) or (b(prior + 2) shl 6) or
            (b(earlier - 2) shl 7) or (b(earlier - 1) shl 8) or
            (b(earlier) shl 9) or (b(earlier + 1) shl 10) or (b(earlier + 2) shl 11)
    }

    private fun updateCounts(counts: IntArray, offset: Int, bit: Int) {
        counts[offset + bit] = (counts[offset + bit] + INCREMENT) and MASK16
        if (counts[offset] + counts[offset + 1] >= COUNT_LIMIT) {
            counts[offset] = halveGuarded(counts[offset])
            counts[offset + 1] = halveGuarded(counts[offset + 1])
        }
    }

    internal fun decodeOneBitRaster(payload: String, width: Int, height: Int): ByteArray {
        val decoder = RangeDecoder(unpack(payload.substring(1)))
        val counts = IntArray(CONTEXT_COUNT * 2) { 1 }
        val paddedWidth = width + 2 * PADDING
        val padded = ByteArray(paddedWidth * (height + PADDING))
        val output = ByteArray(width * height)
        for (y in 0 until height) {
            val row = (y + PADDING) * paddedWidth + PADDING
            for (x in 0 until width) {
                val position = row + x
                val context = contextAt(padded, position, position - paddedWidth, position - 2 * paddedWidth)
                val offset = context shl 1
                val zero = counts[offset]
                val one = counts[offset + 1]
                val value = decoder.frequency(zero + one)
                val bit = if (value < zero) 0 else 1
                if (bit == 0) decoder.decode(0, zero) else decoder.decode(zero, one)
                padded[position] = bit.toByte()
                output[y * width + x] = bit.toByte()
                updateCounts(counts, offset, bit)
            }
        }
        return output
    }

    /** Re-encodes a binary raster; used to verify one_bit canonical form. */
    internal fun encodeOneBitRaster(raster: ByteArray, width: Int, height: Int): String {
        val encoder = RangeEncoder()
        val counts = IntArray(CONTEXT_COUNT * 2) { 1 }
        val paddedWidth = width + 2 * PADDING
        val padded = ByteArray(paddedWidth * (height + PADDING))
        for (y in 0 until height) {
            val row = (y + PADDING) * paddedWidth + PADDING
            for (x in 0 until width) {
                val position = row + x
                val context = contextAt(padded, position, position - paddedWidth, position - 2 * paddedWidth)
                val offset = context shl 1
                val zero = counts[offset]
                val one = counts[offset + 1]
                val bit = raster[y * width + x].toInt()
                if (bit != 0 && bit != 1) throw MaskDecodeException("Decoded mask is not binary.")
                if (bit == 0) encoder.encode(0, zero, zero + one)
                else encoder.encode(zero, one, zero + one)
                padded[position] = bit.toByte()
                updateCounts(counts, offset, bit)
            }
        }
        return "!" + pack(encoder.finish())
    }
}
