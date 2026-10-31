package com.samosaai.masala.miniapps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiniAppIconTest {
    @Test
    fun `null and blank have no icon`() {
        assertNull(MiniAppIcon.sanitize(null))
        assertNull(MiniAppIcon.sanitize(""))
        assertNull(MiniAppIcon.sanitize("   "))
    }

    @Test
    fun `a built-in name is stored with the symbol prefix`() {
        assertEquals("symbol:timer", MiniAppIcon.sanitize("timer"))
        assertEquals("symbol:timer", MiniAppIcon.sanitize("symbol:timer"))
        assertEquals("symbol:music_note", MiniAppIcon.sanitize("music_note"))
    }

    @Test
    fun `an unknown built-in name has no icon`() {
        assertNull(MiniAppIcon.sanitize("calculate"))
        assertNull(MiniAppIcon.sanitize("symbol:calculate"))
        assertNull(MiniAppIcon.sanitize("symbol:"))
    }

    @Test
    fun `a small svg is stored as-is`() {
        val svg = """<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/></svg>"""
        assertEquals(svg, MiniAppIcon.sanitize(svg))
    }

    @Test
    fun `svg at exactly the limit is kept, one char over is not`() {
        val head = "<svg viewBox=\"0 0 24 24\">"
        val tail = "</svg>"
        val ok = head + "x".repeat(MiniAppIcon.MAX_ICON_CHARS - head.length - tail.length) + tail
        assertEquals(MiniAppIcon.MAX_ICON_CHARS, ok.length)
        assertEquals(ok, MiniAppIcon.sanitize(ok))
        assertNull(MiniAppIcon.sanitize(ok.dropLast(6) + "xx</svg>"))
    }

    @Test
    fun `anything that is not a small svg has no icon`() {
        assertNull(MiniAppIcon.sanitize("<html><body>hi</body></html>"))
        assertNull(MiniAppIcon.sanitize("timer <svg viewBox=\"0 0 1 1\"></svg>"))
    }

    @Test
    fun `svg with scripts, frames, images or remote loads has no icon`() {
        val bad = listOf(
            """<svg viewBox="0 0 1 1"><script>alert(1)</script></svg>""",
            """<svg viewBox="0 0 1 1"><SCRIPT>alert(1)</SCRIPT></svg>""",
            """<svg viewBox="0 0 1 1"><foreignObject></foreignObject></svg>""",
            """<svg viewBox="0 0 1 1"><image href="x"/></svg>""",
            """<svg viewBox="0 0 1 1"><a href="http://example.com"/></svg>""",
            """<svg viewBox="0 0 1 1"><a href='http://example.com'/></svg>""",
            """<svg viewBox="0 0 1 1"><rect fill="url(http://example.com/x)"/></svg>""",
        )
        bad.forEach { assertNull(MiniAppIcon.sanitize(it), it) }
    }

    @Test
    fun `svg with local references is kept`() {
        val svg = """<svg viewBox="0 0 24 24"><defs><linearGradient id="g"></linearGradient></defs><rect fill="url(#g)"/><a href="#g"/></svg>"""
        assertEquals(svg, MiniAppIcon.sanitize(svg))
    }
}
