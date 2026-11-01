package com.samosaai.masala.core

import com.samosaai.masala.core.layout.IconSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IconSpecTest {
    @Test
    fun `blank is no icon`() {
        assertNull(IconSpec.sanitize(null))
        assertNull(IconSpec.sanitize(""))
        assertNull(IconSpec.sanitize("   "))
    }

    @Test
    fun `a built-in name becomes a symbol spec`() {
        assertEquals("symbol:timer", IconSpec.sanitize("timer"))
        assertEquals("symbol:timer", IconSpec.sanitize("symbol:timer"))
        assertEquals("symbol:calculate", IconSpec.sanitize("calculate"))
        assertTrue(IconSpec.isSymbol("symbol:timer"))
        assertEquals("timer", IconSpec.symbolName("symbol:timer"))
    }

    @Test
    fun `an invented name is refused`() {
        assertNull(IconSpec.sanitize("rocket"))
        assertNull(IconSpec.sanitize("symbol:rocket"))
        assertNull(IconSpec.sanitize("symbol:"))
        assertFalse(IconSpec.isSymbol("<svg/>"))
        assertNull(IconSpec.symbolName("<svg/>"))
    }

    @Test
    fun `one small inline SVG is kept, its prolog stripped`() {
        val svg = "<?xml version=\"1.0\"?>\n<svg viewBox=\"0 0 24 24\"><path d=\"M0 0h24v24H0z\"/></svg>"
        assertEquals("<svg viewBox=\"0 0 24 24\"><path d=\"M0 0h24v24H0z\"/></svg>", IconSpec.sanitize(svg))
    }

    @Test
    fun `an SVG that reaches outside, or is too big, is refused`() {
        listOf(
            "<svg onload=\"x\"><script>alert(1)</script></svg>",
            "<svg><image href=\"http://example.com/a.png\"/></svg>",
            "<svg><use href=\"http://example.com/a#b\"/></svg>",
            "<svg><foreignObject/></svg>",
            "<div>not an svg</div>",
            "<svg>" + "a".repeat(IconSpec.MAX_CHARS) + "</svg>",
        ).forEach { assertNull(IconSpec.sanitize(it), it.take(40)) }
    }
}
