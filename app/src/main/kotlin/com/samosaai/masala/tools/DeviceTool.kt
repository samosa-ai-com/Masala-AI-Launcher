package com.samosaai.masala.tools

import com.samosaai.masala.core.tool.ActionResult
import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class DeviceTool(private val context: Context) {

    /**
     * Turn the camera flash on or off. No permission needed for CameraManager.setTorchMode.
     *
     * Unlike the other device tools this does *not* report a previous state.
     * `CameraManager` has no synchronous torch getter — the only way to read it is
     * `registerTorchCallback`, which delivers asynchronously on a handler thread.
     * The point of prior-state reporting is to surface changes the user cannot
     * observe, and a flashlight is the one change here they always can.
     */
    fun toggleTorch(on: Boolean?, durationSeconds: Int?): ActionResult {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ActionResult.error("This device has no controllable flashlight.")

            // No synchronous torch getter exists: the callback is the only way to learn whether
            // it is already on, which "toggle" needs. Bounded, because a silent callback must not
            // hang the tool.
            val target = on ?: !isTorchOn(cm, cameraId)
            cm.setTorchMode(cameraId, target)
            if (target && durationSeconds != null && durationSeconds > 0) {
                val ms = durationSeconds.coerceIn(1, 300) * 1000L
                Handler(Looper.getMainLooper()).postDelayed({
                    try { cm.setTorchMode(cameraId, false) } catch (_: Exception) {}
                }, ms)
                return ActionResult.ok("Flashlight turned on for ${durationSeconds}s.")
            }
            ActionResult.ok("Flashlight turned ${if (target) "on" else "off"}.")
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if ("in use" in msg || "already" in msg) {
                ActionResult.error("Another app may be using the camera. Close other camera apps and try again.")
            } else {
                ActionResult.error("Could not toggle the flashlight: ${e.message}")
            }
        }
    }

    /** Reads the torch state through the camera callback; false when it never answers. */
    private fun isTorchOn(cm: CameraManager, cameraId: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var on = false
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) {
                    on = enabled
                    latch.countDown()
                }
            }
        }
        return try {
            cm.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
            // The current state is delivered on registration; wait briefly for it.
            latch.await(700, java.util.concurrent.TimeUnit.MILLISECONDS)
            on
        } catch (_: Exception) {
            false
        } finally {
            runCatching { cm.unregisterTorchCallback(callback) }
        }
    }

    /** Set a volume stream to a percentage. Reports previous level. */
    fun setVolume(stream: String, percent: Int, showUi: Boolean): ActionResult {
        if (percent !in 0..100) return ActionResult.error("Volume must be between 0 and 100 (got $percent).")
        val streamType = when (stream.lowercase().trim()) {
            "media", "music" -> AudioManager.STREAM_MUSIC
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> return ActionResult.error(
                "Unknown volume stream '$stream'. Use media, ring, alarm, notification, or call."
            )
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(streamType)
            val previousPct = (am.getStreamVolume(streamType) * 100) / max
            val target = (percent * max) / 100
            val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0
            am.setStreamVolume(streamType, target, flags)
            ActionResult.ok("Set $stream volume from $previousPct% to $percent%.")
        } catch (_: SecurityException) {
            ActionResult.permissionNeeded(
                ActionResult.DND_ACCESS,
                "Changing this volume needs Do Not Disturb access. I have opened that settings page — please enable it and ask again."
            )
        } catch (e: Exception) {
            ActionResult.error("Could not set the volume: ${e.message}")
        }
    }

    /** Read current volume level(s). */
    fun getVolume(stream: String?): ActionResult {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streams = if (stream != null) {
                listOf(stream.lowercase().trim())
            } else {
                listOf("media", "ring", "alarm", "notification", "call")
            }
            val results = streams.map { name ->
                val type = when (name) {
                    "media", "music" -> AudioManager.STREAM_MUSIC
                    "ring", "ringer" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "call", "voice" -> AudioManager.STREAM_VOICE_CALL
                    else -> return ActionResult.error(
                        "Unknown stream '$name'. Use media, ring, alarm, notification, or call."
                    )
                }
                val max = am.getStreamMaxVolume(type)
                val current = am.getStreamVolume(type)
                val pct = (current * 100) / max
                "$name: $pct%"
            }
            ActionResult.ok(results.joinToString("\n"))
        } catch (e: Exception) {
            ActionResult.error("Could not read volume: ${e.message}")
        }
    }

    /** Set the ringer mode: normal / vibrate / silent. Silent & vibrate need DND access on N+. */
    fun setRingerMode(mode: String): ActionResult {
        val ringerMode = when (mode.lowercase().trim()) {
            "normal", "loud" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
            else -> return ActionResult.error("Unknown ringer mode '$mode'. Use normal, vibrate, or silent.")
        }
        if (ringerMode != AudioManager.RINGER_MODE_NORMAL && !hasDndAccess()) {
            return ActionResult.permissionNeeded(
                ActionResult.DND_ACCESS,
                "Silencing the ringer needs Do Not Disturb access. I have opened that settings page — please enable it and ask again."
            )
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val previous = ringerLabel(am.ringerMode)
            am.ringerMode = ringerMode
            ActionResult.ok("Ringer $previous → ${mode.lowercase().trim()}.")
        } catch (_: SecurityException) {
            ActionResult.permissionNeeded(
                ActionResult.DND_ACCESS,
                "Changing the ringer needs Do Not Disturb access. I have opened that settings page — please enable it and ask again."
            )
        } catch (e: Exception) {
            ActionResult.error("Could not set the ringer mode: ${e.message}")
        }
    }

    /** Vibrate with optional intensity and pattern (needs VIBRATE, an install-time permission). */
    fun vibrate(durationMs: Int, intensity: Int, pattern: String?): ActionResult {
        val vibrator = getVibrator() ?: return ActionResult.error("This device has no vibrator.")
        val amplitude = (intensity.coerceIn(0, 100) * 255) / 100
        val effect = if (pattern != null) {
            patternEffect(pattern.lowercase().trim(), amplitude)
        } else {
            val ms = durationMs.coerceIn(1, 5000).toLong()
            if (amplitude == 0) {
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                VibrationEffect.createOneShot(ms, amplitude)
            }
        }
        return try {
            vibrator.vibrate(effect)
            val desc = if (pattern != null) " (pattern: $pattern)" else " for ${durationMs.coerceIn(1, 5000)}ms"
            ActionResult.ok("Vibrated$desc.")
        } catch (e: Exception) {
            ActionResult.error("Could not vibrate: ${e.message}")
        }
    }

    private fun patternEffect(pattern: String, amplitude: Int): VibrationEffect {
        val timings = when (pattern) {
            "short" -> longArrayOf(0, 100)
            "long" -> longArrayOf(0, 1000)
            "double" -> longArrayOf(0, 200, 800, 200)
            "sos" -> longArrayOf(0, 200, 200, 200, 200, 200, 600, 600, 600, 200, 600, 200, 600, 600, 200, 200, 200, 200, 200)
            else -> longArrayOf(0, 500)
        }
        val amps = if (amplitude == 0) null else IntArray(timings.size) { amplitude }
        return if (amps != null) {
            VibrationEffect.createWaveform(timings, amps, -1)
        } else {
            @Suppress("DEPRECATION")
            VibrationEffect.createWaveform(timings, -1)
        }
    }

    private fun getVibrator(): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        } catch (_: Exception) { null }
    }

    /** Turn Do Not Disturb on or off (needs DND / notification-policy access). */
    fun setDnd(enabled: Boolean): ActionResult {
        if (!hasDndAccess()) {
            return ActionResult.permissionNeeded(
                ActionResult.DND_ACCESS,
                "Do Not Disturb control needs notification-policy access. " +
                    "I have opened that settings page — please enable it and ask again."
            )
        }
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val wasOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            nm.setInterruptionFilter(
                if (enabled) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                }
            )
            ActionResult.ok(
                "Do Not Disturb ${if (wasOn) "on" else "off"} → ${if (enabled) "on" else "off"}."
            )
        } catch (e: Exception) {
            ActionResult.error("Could not change Do Not Disturb: ${e.message}")
        }
    }

    private fun ringerLabel(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_SILENT -> "silent"
        else -> "unknown"
    }

    private fun hasDndAccess(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }
}
