package com.samosaai.masala.tools.sam

import com.samosaai.masala.tools.SamShared
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SamRecordsTest {
    private val wire =
        "<0f>0<|box;x1=211;y1=228;x2=270;y2=254;w=320;h=334|><|mask;x=0;y=0;data=27,60,~abc|>" +
            ",1<|box;x1=155;y1=228;x2=202;y2=254;w=320;h=334;c=0.96|><|mask;x=0;y=0;c=0.96;data=27,48,!def|>"

    @Test
    fun `parseRecords pairs boxes with masks in order`() {
        val records = SamShared.parseRecords(wire)
        assertEquals(2, records.size)
        assertEquals(0, records[0].id)
        assertEquals(SamShared.RichBox(211, 228, 270, 254, 320, 334), records[0].box)
        assertEquals(27, records[0].maskHeight)
        assertEquals(60, records[0].maskWidth)
        assertEquals("~abc", records[0].maskPayload)
        assertEquals(1, records[1].id)
        assertEquals("!def", records[1].maskPayload)
    }

    @Test
    fun `parseRecords is empty on empty or model text`() {
        assertTrue(SamShared.parseRecords("").isEmpty())
        assertTrue(SamShared.parseRecords("no matches here").isEmpty())
    }

    @Test
    fun `scaleBox uses the frame size not the largest edge`() {
        // Regression test for the old max-edge scaling: a 320x334 frame drawn
        // on a 640x668 bitmap must scale both axes by 2, wherever the boxes sit.
        val box = SamShared.RichBox(211, 228, 270, 254, 320, 334)
        val s = SamShared.scaleBox(box, 640, 668)
        assertEquals(422f, s[0])
        assertEquals(456f, s[1])
        assertEquals(540f, s[2])
        assertEquals(508f, s[3])
    }
}
