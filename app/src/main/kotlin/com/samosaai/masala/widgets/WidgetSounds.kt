package com.samosaai.masala.widgets

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager

/**
 * The sounds a widget can make (plan `docs/plans/widget-gallery.md` §4). Kept to two: a click for
 * buttons, and the device's own notification tone for a timer that reaches zero — so the phone's
 * sound settings decide the volume and whether it rings at all.
 *
 * Never throws: a silent phone or a missing ringtone is not an error worth interrupting a tap for.
 */
object WidgetSounds {

    fun click(context: Context) {
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audio.playSoundEffect(AudioManager.FX_KEY_CLICK)
        }
    }

    fun chime(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return@runCatching
            RingtoneManager.getRingtone(context, uri)?.play()
        }
    }
}
