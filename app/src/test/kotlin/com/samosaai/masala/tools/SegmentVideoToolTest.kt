package com.samosaai.masala.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentVideoToolTest {
    private val multiFrame =
        "<0f>0<|box;x1=199;y1=366;x2=260;y2=438;w=768;h=768|><|mask;x=0;y=0;data=1|>" +
            "<1f>0<|box;x1=210;y1=360;x2=272;y2=440;w=768;h=768|>" +
            "<2f>0<|box;x1=300;y1=100;x2=350;y2=150;w=768;h=768|>" +
            "1<|box;x1=400;y1=200;x2=450;y2=250;w=768;h=768|>"

    @Test
    fun `parseVideoFrames splits multi-frame groups`() {
        val frames = SamShared.parseVideoFrames(multiFrame)
        assertEquals(3, frames.size)
        assertEquals(0, frames[0].frame)
        assertEquals(1, frames[1].frame)
        assertEquals(2, frames[2].frame)
        assertEquals(
            SamShared.RichBox(199, 366, 260, 438, 768, 768),
            frames[0].boxes.single(),
        )
        assertEquals(1, frames[1].boxes.size)
        assertEquals(2, frames[2].boxes.size)
        assertEquals(SamShared.RichBox(400, 200, 450, 250, 768, 768), frames[2].boxes[1])
    }

    @Test
    fun `parseVideoFrames reads one frame`() {
        val frames = SamShared.parseVideoFrames(
            "<0f>0<|box;x1=80;y1=120;x2=240;y2=180;w=320;h=240|>",
        )
        assertEquals(1, frames.size)
        assertEquals(0, frames[0].frame)
        assertEquals(SamShared.RichBox(80, 120, 240, 180, 320, 240), frames[0].boxes.single())
    }

    @Test
    fun `parseVideoFrames is empty with no boxes`() {
        assertTrue(SamShared.parseVideoFrames("").isEmpty())
        assertTrue(SamShared.parseVideoFrames("no matches here").isEmpty())
        assertTrue(SamShared.parseVideoFrames("<0f>2<|eos|>").all { it.boxes.isEmpty() })
    }

    @Test
    fun `contactTimesMs spreads across the clip`() {
        assertEquals(listOf(2250L, 4500L, 6750L), SamShared.contactTimesMs(9000L, 3))
        assertEquals(listOf(4500L), SamShared.contactTimesMs(9000L, 1))
        assertTrue(SamShared.contactTimesMs(0L, 3).isEmpty())
        assertTrue(SamShared.contactTimesMs(9000L, 0).isEmpty())
    }

    @Test
    fun `contactFrameIndexes picks first middle last`() {
        assertEquals(listOf(0, 9, 19), SamShared.contactFrameIndexes(20))
        assertEquals(listOf(0, 1), SamShared.contactFrameIndexes(2))
        assertEquals(listOf(0), SamShared.contactFrameIndexes(1))
        assertTrue(SamShared.contactFrameIndexes(0).isEmpty())
    }

    @Test
    fun `checkVideoSize refuses an oversized clip`() {
        assertNotNull(SamShared.checkVideoSize(SamShared.MAX_VIDEO_BYTES + 1))
        assertNull(SamShared.checkVideoSize(SamShared.MAX_VIDEO_BYTES))
        assertNull(SamShared.checkVideoSize(1024))
    }

    @Test
    fun `clampMaxFrames defaults and caps`() {
        assertEquals(8, SamShared.clampMaxFrames(null))
        assertEquals(16, SamShared.clampMaxFrames(100))
        assertEquals(1, SamShared.clampMaxFrames(0))
        assertEquals(4, SamShared.clampMaxFrames(4))
    }

    @Test
    fun `videoMimeFor maps extensions`() {
        assertEquals("video/mp4", SamShared.videoMimeFor("clip.MP4"))
        assertEquals("video/quicktime", SamShared.videoMimeFor("clip.mov"))
        assertEquals("video/webm", SamShared.videoMimeFor("clip.webm"))
        assertEquals("video/mp4", SamShared.videoMimeFor("clip.unknown"))
    }

    @Test
    fun `scaleBox maps model space onto the frame`() {
        val scaled = SamShared.scaleBox(SamShared.RichBox(199, 366, 260, 438, 768, 768), 384, 384)
        assertEquals(99.5f, scaled[0])
        assertEquals(183f, scaled[1])
        assertEquals(130f, scaled[2])
        assertEquals(219f, scaled[3])
    }

    @Test
    fun `framesJson writes frame box lists`() {
        val frames = SamShared.parseVideoFrames(multiFrame).filter { it.boxes.isNotEmpty() }
        val parsed = SamShared.framesJson(frames)
        assertTrue(parsed.startsWith("[{\"frame\":0,"))
        assertTrue(parsed.contains("\"x1\":199"))
        assertTrue(parsed.contains("\"frame\":2"))
    }

    @Test
    fun `parseVideoRecords groups records per frame`() {
        val wire = "<0f>0<|box;x1=80;y1=120;x2=240;y2=180;w=320;h=240|><|mask;x=0;y=0;data=61,161,~abc|>" +
            "<1f>0<|box;x1=90;y1=130;x2=250;y2=190;w=320;h=240|>" +
            "<2f>"
        val frames = SamShared.parseVideoRecords(wire)
        assertEquals(3, frames.size)
        assertEquals(0, frames[0].frame)
        assertEquals(0, frames[0].records.single().id)
        assertEquals(SamShared.RichBox(80, 120, 240, 180, 320, 240), frames[0].records.single().box)
        assertEquals(61, frames[0].records.single().maskHeight)
        assertEquals(161, frames[0].records.single().maskWidth)
        assertEquals("~abc", frames[0].records.single().maskPayload)
        assertEquals(1, frames[0].boxes.size)
        assertTrue(frames[1].records.isEmpty())
        assertEquals(1, frames[1].boxes.size)
        assertTrue(frames[2].records.isEmpty())
        assertTrue(frames[2].boxes.isEmpty())
    }

    @Test
    fun `parseVideoRecords is one frame without markers`() {
        val frames = SamShared.parseVideoRecords(
            "0<|box;x1=80;y1=120;x2=240;y2=180;w=320;h=240|><|mask;x=0;y=0;data=61,161,~abc|>",
        )
        assertEquals(1, frames.size)
        assertEquals(0, frames[0].frame)
        assertEquals(1, frames[0].records.size)
        assertTrue(SamShared.parseVideoRecords("").isEmpty())
        assertTrue(SamShared.parseVideoRecords("no matches here").isEmpty())
    }

    @Test
    fun `videoFrameTimesMs spreads api frames across the clip`() {
        assertEquals(listOf(2250L, 4500L, 6750L), SamShared.videoFrameTimesMs(3, 9000L))
        assertEquals(listOf(4500L), SamShared.videoFrameTimesMs(1, 9000L))
        assertTrue(SamShared.videoFrameTimesMs(0, 9000L).isEmpty())
        assertTrue(SamShared.videoFrameTimesMs(3, 0L).isEmpty())
    }

    @Test
    fun `overlayFps derives from the sampled rate and clamps`() {
        // 8 frames over 10 s samples at ~1 fps: plays at the minimum.
        assertEquals(5, SamShared.overlayFps(8, 10_000L))
        assertEquals(10, SamShared.overlayFps(100, 10_000L))
        // 200 frames over 10 s samples at 20 fps: capped for encode speed.
        assertEquals(15, SamShared.overlayFps(200, 10_000L))
        assertEquals(5, SamShared.overlayFps(0, 10_000L))
        assertEquals(5, SamShared.overlayFps(8, 0L))
        assertEquals(5, SamShared.overlayFps(8, -1L))
    }

    @Test
    fun `overlaySize caps the long side and stays even`() {
        assertEquals(Pair(720, 404), SamShared.overlaySize(1280, 720))
        assertEquals(Pair(404, 720), SamShared.overlaySize(720, 1280))
        assertEquals(Pair(720, 404), SamShared.overlaySize(1920, 1080))
        // Small and odd sources: never upscale, always even.
        assertEquals(Pair(320, 240), SamShared.overlaySize(320, 240))
        assertEquals(Pair(320, 240), SamShared.overlaySize(321, 241))
        // Unreadable source: square fallback.
        assertEquals(Pair(720, 720), SamShared.overlaySize(0, 0))
    }

    @Test
    fun `encodePlan skips frames with no records`() {
        val frames = SamShared.parseVideoRecords(
            "<0f>0<|box;x1=80;y1=120;x2=240;y2=180;w=320;h=240|><|mask;x=0;y=0;data=61,161,~abc|>" +
                "<1f>" +
                "<2f>0<|box;x1=90;y1=130;x2=250;y2=190;w=320;h=240|>",
        )
        val plan = SamShared.encodePlan(frames)
        assertEquals(listOf(0, 2), plan.map { it.frame })
        assertTrue(SamShared.encodePlan(emptyList()).isEmpty())
    }
}
