package com.samosaai.masala

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.widgets.WidgetChecker
import com.samosaai.masala.widgets.StreamKind
import com.samosaai.masala.widgets.StreamSourceStore
import com.samosaai.masala.widgets.StreamTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan `docs/plans/widget-maker.md` §10 on the device: the fake camera on the dev machine
 * (`ffmpeg` MJPEG + snapshot JPEG + progressive MP4, reached at 10.0.2.2) plays through the
 * whole stack — add source, seal nothing (no credentials here), probe it, and point a
 * snapshot widget at it. RTSP/HLS need `mediamtx` and stay manual.
 */
@RunWith(AndroidJUnit4::class)
class StreamMediaTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private val store: StreamSourceStore get() = app.streamSources

    private fun add(name: String, kind: StreamKind, url: String): String = runBlocking {
        val (id, message) = store.add(name, kind, url, null, null, null, null)
        assertTrue(message ?: name, id != null)
        id!!
    }

    @Test
    fun fakeCameraProbes() = runBlocking {
        val mjpeg = add("Fake MJPEG", StreamKind.MJPEG, "http://10.0.2.2:8090/feed.mjpeg")
        val snap = add("Fake snapshot", StreamKind.SNAPSHOT, "http://10.0.2.2:8091/snap.jpg")
        val clip = add("Fake clip", StreamKind.PROGRESSIVE, "http://10.0.2.2:8091/clip.mp4")
        try {
            val mjpegResult = StreamTest.test(app, StreamKind.MJPEG, "http://10.0.2.2:8090/feed.mjpeg", null)
            assertTrue("mjpeg: ${mjpegResult.detail}", mjpegResult.ok)
            val snapResult = StreamTest.test(app, StreamKind.SNAPSHOT, "http://10.0.2.2:8091/snap.jpg", "http://10.0.2.2:8091/snap.jpg")
            assertTrue("snapshot: ${snapResult.detail}", snapResult.ok)
            assertTrue(snapResult.detail, snapResult.detail.contains("320×240"))
            val clipResult = StreamTest.test(app, StreamKind.PROGRESSIVE, "http://10.0.2.2:8091/clip.mp4", null)
            assertTrue("progressive: ${clipResult.detail}", clipResult.ok)

            // A snapshot widget may point at the source by id.
            val spec = com.samosaai.masala.core.contract.WidgetSpec(
                id = "door",
                title = "Door",
                components = listOf(com.samosaai.masala.core.contract.WidgetComponent.Snapshot(snap)),
            )
            assertTrue(WidgetChecker.check(spec, app.layouts.environment()).toString(), WidgetChecker.check(spec, app.layouts.environment()).isEmpty())
            assertEquals(3, store.current().size.let { n -> if (n >= 3) 3 else n })
        } finally {
            store.remove(mjpeg)
            store.remove(snap)
            store.remove(clip)
        }
    }
}
