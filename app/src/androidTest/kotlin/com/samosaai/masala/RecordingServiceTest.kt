package com.samosaai.masala

import android.Manifest
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samosaai.masala.audio.RecordingService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Lane D's recording check: the service starts from an explicit request, writes a playable AAC
 * chunk into app storage, and stops on request.
 */
@RunWith(AndroidJUnit4::class)
class RecordingServiceTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()

    @Before
    fun grantMic() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun serviceRecordsAChunkAndStops() {
        val dir = File(app.filesDir, "recordings")
        dir.deleteRecursively()

        ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
        Thread.sleep(4_000)
        // Stop the way the notification does, so the recorder finishes its MP4 header properly.
        app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        Thread.sleep(1_500)

        val chunks = dir.listFiles { f -> f.name.startsWith("rec-") && f.name.endsWith(".m4a") }.orEmpty()
        assertTrue("no recording written", chunks.isNotEmpty())
        val file = chunks.maxByOrNull { it.name }!!
        assertTrue("recording is empty (${file.length()} bytes)", file.length() > 1_000)
        // MP4/M4A container: the 'ftyp' box sits near the start.
        val head = file.inputStream().use { it.readNBytes(12) }
        assertTrue("not an MP4 container", String(head, Charsets.ISO_8859_1).contains("ftyp"))
        assertFalse("service still reports recording", RecordingService.recording.value)
    }
}
