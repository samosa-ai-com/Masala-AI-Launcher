package com.samosaai.masala.widgets

import android.content.Context
import android.graphics.BitmapFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.samosaai.masala.core.widgets.MjpegParser
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * `test_stream` behind the tool and the manager's Test button (plan `docs/plans/widget-maker.md`
 * §4): connects for at most [TIMEOUT_MS], then names the codec, the resolution and the latency —
 * or the error. Player kinds probe through a muted throwaway ExoPlayer on the main thread;
 * MJPEG and snapshot kinds probe over plain HTTP. Nothing is kept: no recording, no state.
 */
object StreamTest {

    data class Result(val ok: Boolean, val detail: String)

    suspend fun test(context: Context, kind: StreamKind, streamUrl: String, snapshotUrl: String?): Result =
        when (kind) {
            StreamKind.MJPEG -> httpProbe(streamUrl, mjpeg = true)
            StreamKind.SNAPSHOT -> httpProbe(snapshotUrl ?: streamUrl, mjpeg = false)
            StreamKind.RTSP, StreamKind.HLS, StreamKind.DASH, StreamKind.PROGRESSIVE ->
                playerProbe(context, streamUrl)
        }

    private suspend fun httpProbe(url: String, mjpeg: Boolean): Result = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val elapsed = { System.currentTimeMillis() - start }
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS.toInt()
                readTimeout = TIMEOUT_MS.toInt()
                instanceFollowRedirects = true
                setRequestProperty("Accept", if (mjpeg) "multipart/x-mixed-replace" else "image/*")
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                return@withContext Result(false, "HTTP ${connection.responseCode}")
            }
            if (mjpeg) {
                val boundary = connection.contentType?.let(::multipartBoundary)
                val parser = MjpegParser(boundary)
                val buffer = ByteArray(32 * 1024)
                val deadline = start + TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    val read = connection.inputStream.read(buffer)
                    if (read < 0) break
                    val frames = parser.feed(buffer, 0, read)
                    val frame = frames.firstOrNull()
                    if (frame != null) {
                        val size = bitmapSize(frame) ?: return@withContext Result(false, "The stream answered but its first frame isn't an image")
                        return@withContext Result(true, "${size.first}×${size.second} mjpeg, ${elapsed()} ms")
                    }
                }
                Result(false, "No frame arrived in ${TIMEOUT_MS / 1000} s")
            } else {
                val bytes = connection.inputStream.readBytesCapped(MAX_SNAPSHOT_BYTES)
                val size = bitmapSize(bytes) ?: return@withContext Result(false, "That URL isn't an image")
                Result(true, "${size.first}×${size.second} jpeg, ${elapsed()} ms")
            }
        } catch (e: Exception) {
            Result(false, short(e))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private suspend fun playerProbe(context: Context, url: String): Result = withContext(Dispatchers.Main) {
        val start = System.currentTimeMillis()
        val player = ExoPlayer.Builder(context).build().apply { volume = 0f }
        try {
            val outcome = withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<Probed> { cont ->
                    val listener = object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY && cont.isActive) cont.resume(Probed.READY)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (cont.isActive) cont.resume(Probed.ERROR)
                        }
                    }
                    player.addListener(listener)
                    cont.invokeOnCancellation { runCatching { player.removeListener(listener) } }
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                }
            } ?: return@withContext Result(false, "Nothing played in ${TIMEOUT_MS / 1000} s")
            if (outcome == Probed.ERROR) return@withContext Result(false, player.playerError?.message?.take(160) ?: "The stream didn't play")
            val video = player.videoFormat
            val size = if (video != null && video.width > 0) "${video.width}×${video.height} " else ""
            val codec = video?.sampleMimeType?.substringAfterLast('/')?.let { "$it, " }.orEmpty()
            Result(true, "$size${codec}${System.currentTimeMillis() - start} ms")
        } catch (e: Exception) {
            Result(false, short(e))
        } finally {
            runCatching { player.release() }
        }
    }

    private enum class Probed { READY, ERROR }

    private fun bitmapSize(bytes: ByteArray): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }

    private fun multipartBoundary(contentType: String): String? =
        contentType.split(';').map { it.trim() }.firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
            ?.substringAfter('=')?.trim('"')

    private fun java.io.InputStream.readBytesCapped(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun short(e: Exception) = (e.message ?: e.javaClass.simpleName).take(160)

    internal const val TIMEOUT_MS = 8_000L
    internal const val MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024
}
