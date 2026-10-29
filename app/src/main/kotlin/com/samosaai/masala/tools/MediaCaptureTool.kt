package com.samosaai.masala.tools

import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.ToolFiles
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * From the private prototype's MediaCaptureTool, audio only (plan Phase 1, lane B): no photos, and recordings are
 * files under [ToolFiles.workDir] instead of the private prototype's MediaStore entries. Recording in the
 * background needs the recording service (Phase 3); until then it works while Masala is on screen.
 */
class MediaCaptureTool(private val context: Context, private val files: ToolFiles) {

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingDisplayPath: String = ""
    private var recordingStartTime: Long = 0L

    /** When MediaRecorder stopped itself at max_duration_seconds; null while it's still running. */
    @Volatile private var limitReachedAt: Long? = null
    private var recordingPaused: Boolean = false

    /**
     * Where the last completed recording was saved, and how long it ran.
     * Kept so a repeated [stopAudioRecording] can report the already-saved file
     * instead of a bare "no recording in progress" error — models routinely
     * issue a second, redundant stop, and an error there reads as a failure.
     */
    private var lastSavedPath: String? = null
    private var lastSavedDurationSeconds: Long = 0L

    fun startAudioRecording(
        source: String? = null,
        maxDurationSeconds: Int? = null,
        outputPath: String? = null,
        quality: String? = null
    ): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.permissionNeeded(
                Manifest.permission.RECORD_AUDIO,
                "The Microphone permission is not granted. Go to Settings → Permissions → Microphone and enable it, then ask again."
            )
        }
        if (recorder != null) {
            return ActionResult.error(
                "A recording is already in progress. You may stop it with stop_audio_recording before starting a new one, or check " +
                    "status with get_audio_recording_status."
            )
        }
        // Drop the previous recording's details up front: if this start fails, a
        // following stop must report the real error, not a stale saved file.
        lastSavedPath = null
        lastSavedDurationSeconds = 0L
        recordingFile = null
        return try {
            resolveRecordingOutputTarget(outputPath)?.let { return it }

            val rec = createConfiguredRecorder(source, quality, maxDurationSeconds)
            rec.prepare()
            rec.start()
            recorder = rec
            recordingStartTime = System.currentTimeMillis()
            recordingPaused = false

            val extras = buildString {
                if (source != null) append(" ($source)")
                if (maxDurationSeconds != null && maxDurationSeconds > 0) append(", max ${maxDurationSeconds}s")
                append(" at $recordingDisplayPath")
                if (quality != null) append(", $quality quality")
            }
            ActionResult.ok("Recording started$extras.")
        } catch (e: Exception) {
            recordingFile?.delete()
            releaseRecorder()
            ActionResult.error("Could not start recording: ${e.message}")
        }
    }

    /**
     * Resolves where the recording will be written, setting recordingFile and
     * recordingDisplayPath. Returns a ActionResult to return early from
     * startAudioRecording on failure, or null on success.
     */
    private fun resolveRecordingOutputTarget(outputPath: String?): ActionResult? {
        if (!outputPath.isNullOrBlank()) {
            val resolved = files.resolveForWrite(outputPath)
            val file = when (resolved) {
                is ToolFiles.ResolveResult.Ok -> resolved.file.also { it.parentFile?.mkdirs() }
                is ToolFiles.ResolveResult.PermissionNeeded -> return resolved.result
                is ToolFiles.ResolveResult.Error -> return ActionResult.error(resolved.message)
            }
            recordingFile = file
            recordingDisplayPath = file.absolutePath
        } else {
            val file = File(files.workDir, "recordings/recording_${timestamp()}.m4a").also { it.parentFile?.mkdirs() }
            recordingFile = file
            recordingDisplayPath = file.absolutePath
        }
        return null
    }

    private fun createConfiguredRecorder(
        source: String?,
        quality: String?,
        maxDurationSeconds: Int?
    ): MediaRecorder {
        val audioSource = when (source?.trim()?.lowercase()) {
            "voice" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            "camcorder" -> MediaRecorder.AudioSource.CAMCORDER
            else -> MediaRecorder.AudioSource.MIC
        }

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.setAudioSource(audioSource)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

        when (quality?.trim()?.lowercase()) {
            "low" -> {
                rec.setAudioSamplingRate(16000)
                rec.setAudioEncodingBitRate(16000)
            }
            "medium" -> {
                rec.setAudioSamplingRate(44100)
                rec.setAudioEncodingBitRate(64000)
            }
            "high" -> {
                rec.setAudioSamplingRate(44100)
                rec.setAudioEncodingBitRate(192000)
            }
        }

        if (maxDurationSeconds != null && maxDurationSeconds > 0) {
            rec.setMaxDuration(maxDurationSeconds * 1000)
            // The recorder stops on its own at the limit; remember when, so status and the saved
            // duration don't keep counting (the private prototype reported the time until stop was called).
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) limitReachedAt = System.currentTimeMillis()
            }
        }
        rec.setOutputFile(recordingFile!!.absolutePath)
        return rec
    }

    fun stopAudioRecording(): ActionResult {
        val rec = recorder ?: return alreadyStoppedResult()
        return try {
            rec.stop()
            val path = recordingDisplayPath
            val dur = ((limitReachedAt ?: System.currentTimeMillis()) - recordingStartTime) / 1000
            releaseRecorder()
            lastSavedPath = path
            lastSavedDurationSeconds = dur
            ActionResult.ok("Recording saved to $path (${formatDuration(dur)}).")
        } catch (e: Exception) {
            // The encoder can fail (no input, another app took the mic). Keep the user informed
            // about the file instead of only saying "stop failed".
            val path = recordingDisplayPath
            val file = recordingFile
            releaseRecorder()
            val size = file?.length() ?: 0L
            if (file != null && size == 0L) runCatching { file.delete() }
            ActionResult.error(
                "Could not stop the recording cleanly: ${e.message}. " +
                    if (size == 0L) {
                        "No audio was written to '$path', so the empty file was removed."
                    } else {
                        "'$path' may still hold ${size / 1024} KB of audio; try transcribing it, or record again."
                    },
            )
        }
    }

    /**
     * Nothing is recording. If a recording was already stopped and saved, that is
     * the state the caller wanted, so report success with the saved file rather
     * than an error — otherwise a redundant second stop looks like a failure.
     */
    private fun alreadyStoppedResult(): ActionResult {
        val path = lastSavedPath ?: return ActionResult.error(
            "No recording is in progress. Use start_audio_recording to begin one, or check " +
                "status with get_audio_recording_status."
        )
        return ActionResult.ok(
            "No recording is currently running — it was already stopped and saved to $path " +
                "(${formatDuration(lastSavedDurationSeconds)}). Nothing further to do."
        )
    }

    private fun formatDuration(seconds: Long): String = "${seconds / 60}m ${seconds % 60}s"

    fun getAudioRecordingStatus(): ActionResult {
        val rec = recorder
        if (rec == null) return ActionResult.ok("No recording is active.")
        val elapsed = ((limitReachedAt ?: System.currentTimeMillis()) - recordingStartTime) / 1000
        val path = recordingDisplayPath.ifEmpty { "unknown" }
        if (limitReachedAt != null) {
            return ActionResult.ok(
                "Stopped at its time limit after ${formatDuration(elapsed)} at $path; stop_audio_recording saves it.",
            )
        }
        val state = if (recordingPaused) "paused" else "recording"
        return ActionResult.ok("$state for ${formatDuration(elapsed)} at $path.")
    }

    fun pauseAudioRecording(): ActionResult {
        val rec = recorder ?: return ActionResult.error(
            "No recording is in progress. Use start_audio_recording to begin one, or check " +
                "status with get_audio_recording_status."
        )
        if (recordingPaused) {
            return ActionResult.error(
                "Recording is already paused. You may resume it with resume_audio_recording or stop it with stop_audio_recording."
            )
        }
        return try {
            rec.pause()
            recordingPaused = true
            ActionResult.ok("Recording paused.")
        } catch (e: Exception) {
            ActionResult.error("Could not pause: ${e.message}")
        }
    }

    fun resumeAudioRecording(): ActionResult {
        val rec = recorder ?: return ActionResult.error(
            "No recording is in progress. Use start_audio_recording to begin one, or check " +
                "status with get_audio_recording_status."
        )
        if (!recordingPaused) {
            return ActionResult.error(
                "Recording is not paused. Use pause_audio_recording first, or stop the recording with stop_audio_recording."
            )
        }
        return try {
            rec.resume()
            recordingPaused = false
            ActionResult.ok("Recording resumed.")
        } catch (e: Exception) {
            ActionResult.error("Could not resume: ${e.message}")
        }
    }

    private fun releaseRecorder() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        recordingFile = null
        recordingDisplayPath = ""
        recordingStartTime = 0L
        recordingPaused = false
        limitReachedAt = null
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())
}
