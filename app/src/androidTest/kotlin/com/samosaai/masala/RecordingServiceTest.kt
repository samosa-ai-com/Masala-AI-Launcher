package com.samosaai.masala

import android.Manifest
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samosaai.masala.audio.RecordingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    @Test
    fun chunksRotateWhileRecording() {
        val dir = File(app.filesDir, "recordings")
        dir.deleteRecursively()

        val start = Intent(app, RecordingService::class.java).putExtra(RecordingService.EXTRA_CHUNK_SECONDS, 10)
        ContextCompat.startForegroundService(app, start)
        Thread.sleep(25_000)
        app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        Thread.sleep(1_500)

        // 25 s in 10 s chunks: two full ones and the part after.
        val chunks = dir.listFiles { f -> f.name.startsWith("rec-") && f.name.endsWith(".m4a") }.orEmpty().sortedBy { it.name }
        assertEquals("chunks: ${chunks.map { "${it.name}=${it.length()}" }}", 3, chunks.size)
        chunks.forEach { file ->
            val head = file.inputStream().use { it.readNBytes(12) }
            assertTrue("${file.name} is not an MP4 container", String(head, Charsets.ISO_8859_1).contains("ftyp"))
            val ms = android.media.MediaMetadataRetriever().use {
                it.setDataSource(file.absolutePath)
                it.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            }
            assertTrue("${file.name} doesn't play (duration $ms ms)", ms > 1_000)
        }
        assertFalse("service still reports recording", RecordingService.recording.value)
    }

    /** Plan Phase 3 exit, real chunk length: 20 minutes give two files. Opt-in: `am instrument -e long true`. */
    @Test
    fun twentyMinutesGiveTwoFiles() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("long") == "true")
        val dir = File(app.filesDir, "recordings")
        dir.deleteRecursively()

        ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
        Thread.sleep(20 * 60_000L + 5_000)
        app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        Thread.sleep(1_500)

        val chunks = dir.listFiles { f -> f.name.startsWith("rec-") && f.name.endsWith(".m4a") }.orEmpty().sortedBy { it.name }
        val durations = chunks.map { file ->
            android.media.MediaMetadataRetriever().use {
                it.setDataSource(file.absolutePath)
                it.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            }
        }
        android.util.Log.i("RecordingServiceTest", "20 min: ${chunks.map { it.name }} sizes=${chunks.map { it.length() }} ms=$durations")
        assertEquals("chunks: ${chunks.map { it.name }}", 2, chunks.size)
        assertTrue("first chunk isn't ~15 min: $durations", durations[0] in 14 * 60_000L..15 * 60_000L + 2_000)
        assertTrue("second chunk isn't ~5 min: $durations", durations[1] in 4 * 60_000L..6 * 60_000L)
    }
}
