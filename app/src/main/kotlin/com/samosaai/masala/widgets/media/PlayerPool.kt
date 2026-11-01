package com.samosaai.masala.widgets.media

import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * Two live players for home (plan `docs/plans/widget-maker.md` §4.3, D3 fixed): one ExoPlayer per
 * stream URL, muted, LRU-evicted. A third visible video widget falls back to snapshot mode with a
 * Live button that steals the least recently touched slot. Everything here runs on the main
 * thread: ExoPlayer needs a Looper.
 */
class PlayerPool(private val context: Context, private val max: Int = 2) {

    private data class Slot(var player: ExoPlayer, var refs: Int, var lastTouch: Long)

    private val slots = LinkedHashMap<String, Slot>()

    /** Backgrounded (home stopped, screen off): players hold still instead of playing. */
    var backgrounded: Boolean = false
        set(value) {
            field = value
            slots.values.forEach { it.player.playWhenReady = !value }
        }

    /** True while [url] has a live player (its widget draws video, not the snapshot fallback). */
    fun has(url: String): Boolean = slots.containsKey(url)

    /** An unused player for [url], or null when both slots are held by other widgets. */
    @MainThread
    fun acquire(url: String, transport: String?): ExoPlayer? {
        val now = System.currentTimeMillis()
        slots[url]?.let {
            it.refs++
            it.lastTouch = now
            it.player.playWhenReady = !backgrounded
            return it.player
        }
        if (slots.size >= max) return null
        val player = ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = !backgrounded
            prepare()
        }
        slots[url] = Slot(player, 1, now)
        return player
    }

    /** Takes the least recently touched slot for [url], evicting its stream. For the Live button. */
    @MainThread
    fun steal(url: String, transport: String?): ExoPlayer {
        val now = System.currentTimeMillis()
        slots[url]?.let {
            it.refs++
            it.lastTouch = now
            it.player.playWhenReady = !backgrounded
            return it.player
        }
        val oldest = slots.minByOrNull { it.value.lastTouch }?.key
        if (oldest != null) evict(oldest)
        return checkNotNull(acquire(url, transport)) { "Pool without slots" }
    }

    @MainThread
    fun touch(url: String) {
        slots[url]?.let { it.lastTouch = System.currentTimeMillis() }
    }

    @MainThread
    fun release(url: String) {
        val slot = slots[url] ?: return
        slot.refs = (slot.refs - 1).coerceAtLeast(0)
        if (slot.refs == 0 && slots.size > max) evict(url)
    }

    /** Drops players nobody shows (a zero-ref slot past the cap); held slots survive. */
    @MainThread
    fun compact() {
        val spare = slots.filter { it.value.refs == 0 }.keys.toList().dropLast(max.coerceAtMost(slots.size))
        spare.forEach(::evict)
    }

    @MainThread
    fun releaseAll() {
        slots.keys.toList().forEach(::evict)
    }

    @MainThread
    private fun evict(url: String) {
        runCatching {
            slots.remove(url)?.player?.release()
        }
    }
}

/** Whether [url] stays on the LAN (a private host, .local or loopback): live even when metered. */
fun isLanUrl(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host }.getOrNull()?.trimEnd('.') ?: return false
    if (host.equals("localhost", ignoreCase = true) || host == "::1" || host == "127.0.0.1") return true
    if (host.endsWith(".local", ignoreCase = true)) return true
    val v4 = host.split('.')
    if (v4.size == 4 && v4.all { it.toIntOrNull() in 0..255 }) {
        val a = v4[0].toInt()
        val b = v4[1].toInt()
        if (a == 10) return true
        if (a == 172 && b in 16..31) return true
        if (a == 192 && b == 168) return true
        return false
    }
    return false
}

/** Whether the active network is metered: live video drops to snapshot unless LAN or allowed. */
fun Context.isMeteredNow(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    return runCatching { manager.isActiveNetworkMetered }.getOrDefault(false)
}
