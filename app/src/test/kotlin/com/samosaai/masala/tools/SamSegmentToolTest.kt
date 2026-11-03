package com.samosaai.masala.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SamSegmentToolTest {
    @Test
    fun `parseBoxes reads the live wire format`() {
        val wire = "<0f>0<|box;x1=80;y1=120;x2=240;y2=180;w=320;h=240|><|mask;x=0;y=0;data=61,161,~abc|>" +
            ",1<|box;x1=260;y1=382;x2=340;y2=427;w=537;h=561|><|mask;x=0;y=0;data=46,81,~def|>"
        val boxes = SamSegmentTool.parseBoxes(wire)
        assertEquals(2, boxes.size)
        assertEquals(SamSegmentTool.Box(80, 120, 240, 180), boxes[0])
        assertEquals(SamSegmentTool.Box(260, 382, 340, 427), boxes[1])
    }

    @Test
    fun `parseBoxes is empty on empty or model text`() {
        assertTrue(SamSegmentTool.parseBoxes("").isEmpty())
        assertTrue(SamSegmentTool.parseBoxes("no matches here").isEmpty())
    }

    @Test
    fun `appPrivateFile gates to app dirs`() {
        val base = "/data/data/com.samosaai.masala/files"
        val cache = "/data/data/com.samosaai.masala/cache"
        assertEquals(
            "$base/generated/a.png",
            appPrivateFile(base, cache, "$base/generated/a.png")?.path,
        )
        assertEquals(
            "$cache/x.wav",
            appPrivateFile(base, cache, "$cache/x.wav")?.path,
        )
        assertEquals(null, appPrivateFile(base, cache, "relative/a.png"))
        assertEquals(null, appPrivateFile(base, cache, "/sdcard/a.png"))
        assertEquals(null, appPrivateFile(base, cache, "$base/../shared_prefs/a.xml"))
    }
}
