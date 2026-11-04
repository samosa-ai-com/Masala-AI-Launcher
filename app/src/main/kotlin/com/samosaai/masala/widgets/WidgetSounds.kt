package com.samosaai.masala.widgets

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import com.samosaai.masala.R

/**
 * The sounds a widget can make. Kept to two: a click for
 * buttons, and a soft two-note chime for a timer that reaches zero.
 *
 * The chime is **bundled** rather than the system notification tone: reading the user's ringtone
 * through `RingtoneManager` needs a media-read permission a launcher should not ask for, and on a
 * phone without it the timer finished in silence (seen on device, 2026-11-03). A bundled WAV plays
 * on the notification stream with no permission at all, so the phone's volume and silent mode
 * still decide whether it is heard.
 *
 * Never throws: a silent phone or a failed player is not worth interrupting a tap for.
 */
object WidgetSounds {

    /** One at a time: a second chime stops the first instead of overlapping it. */
    private var chimePlayer: MediaPlayer? = null

    fun click(context: Context) {
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audio.playSoundEffect(AudioManager.FX_KEY_CLICK)
        }
    }

    fun chime(context: Context) {
        runCatching {
            chimePlayer?.let { runCatching { it.release() } }
            chimePlayer = null
            val player = MediaPlayer()
            context.resources.openRawResourceFd(R.raw.widget_chime).use { fd ->
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setOnCompletionListener {
                runCatching { it.release() }
                if (chimePlayer === it) chimePlayer = null
            }
            player.setOnErrorListener { p, _, _ ->
                runCatching { p.release() }
                if (chimePlayer === p) chimePlayer = null
                true
            }
            player.prepare()
            player.start()
            chimePlayer = player
        }
    }
}
