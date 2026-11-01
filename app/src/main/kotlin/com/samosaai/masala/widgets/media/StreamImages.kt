package com.samosaai.masala.widgets.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.widgets.MjpegParser
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.widgets.StreamSourceStore
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A still image refreshed from a stream source while visible (plan `docs/plans/widget-maker.md`
 * §4.1): polls every [everySeconds] (at least 2 s), keeps the last frame when a poll fails, and
 * says so instead of going blank. Credentials come from the store, never from the widget.
 */
@Composable
fun SnapshotImage(sourceId: String, everySeconds: Int, store: StreamSourceStore, modifier: Modifier = Modifier) {
    var bitmap by remember(sourceId) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var stale by remember(sourceId) { mutableStateOf(false) }
    var failed by remember(sourceId) { mutableStateOf(false) }
    LaunchedEffect(sourceId, everySeconds) {
        val every = (everySeconds.coerceAtLeast(2) * 1000L)
        while (isActive) {
            val frame = withContext(Dispatchers.IO) { fetchFrame(store.snapshotUrlWithCredentials(sourceId)) }
            if (frame != null) {
                bitmap = frame
                stale = false
                failed = false
            } else if (bitmap == null) {
                failed = true
            } else {
                stale = true
            }
            delay(every)
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null -> {
                Image(image, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), contentScale = ContentScale.FillWidth)
                if (stale) {
                    Text(
                        stringResource(R.string.widgets_snapshot_stale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MasalaTheme.tokens.onWallpaperMedium,
                    )
                }
            }
            failed -> SnapshotError { failed = false }
            else -> Text(stringResource(R.string.widgets_snapshot_loading), style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium)
        }
    }
}

/**
 * A live MJPEG stream (plan §4.1): Media3 has no MJPEG, so a reader thread splits frames with
 * [MjpegParser] and the latest decodes on screen. Stops when the card leaves composition.
 */
@Composable
fun MjpegImage(sourceId: String, store: StreamSourceStore, modifier: Modifier = Modifier) {
    var bitmap by remember(sourceId) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var error by remember(sourceId) { mutableStateOf<String?>(null) }
    var attempt by remember(sourceId) { mutableStateOf(0) }
    DisposableEffect(sourceId, attempt) {
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).let { scope ->
            scope.launchReader(store, sourceId, onFrame = { bitmap = it }, onError = { error = it })
        }
        onDispose { job.cancel() }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val image = bitmap
        val problem = error
        when {
            image != null -> Image(image, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), contentScale = ContentScale.FillWidth)
            problem != null -> SnapshotError(problem) { attempt++ }
            else -> Text(stringResource(R.string.widgets_snapshot_loading), style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium)
        }
    }
}

@Composable
private fun SnapshotError(problem: String? = null, onRetry: () -> Unit) {
    Text(
        problem ?: stringResource(R.string.widgets_snapshot_failed),
        style = MaterialTheme.typography.bodyMedium,
        color = MasalaTheme.tokens.onWallpaperMedium,
    )
    TextButton(onClick = onRetry) { Text(stringResource(R.string.widgets_retry)) }
}

private fun kotlinx.coroutines.CoroutineScope.launchReader(
    store: StreamSourceStore,
    sourceId: String,
    onFrame: (androidx.compose.ui.graphics.ImageBitmap) -> Unit,
    onError: (String) -> Unit,
): kotlinx.coroutines.Job = launch(Dispatchers.IO) {
    val url = store.urlWithCredentials(sourceId) ?: run {
        withContext(Dispatchers.Main) { onError("Unknown source") }
        return@launch
    }
    // One connection per pass: on EOS or a read error the loop re-opens it (capped backoff),
    // so a dropped camera resumes instead of freezing on its last frame. Only a non-2xx
    // status or a dead source ends the loop through onError; the retry button re-enters above.
    var backoffCount = 0L
    while (isActive) {
        var connection: HttpURLConnection? = null
        try {
            val (cleanUrl, auth) = com.samosaai.masala.widgets.StreamAuth.split(url)
            connection = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 0
                setRequestProperty("Accept", "multipart/x-mixed-replace")
                if (auth != null) setRequestProperty("Authorization", auth)
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                withContext(Dispatchers.Main) { onError("HTTP ${connection.responseCode}") }
                return@launch
            }
            backoffCount = 0
            val boundary = connection.contentType?.let { type ->
                type.split(';').map { it.trim() }.firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
                    ?.substringAfter('=')?.trim('"')
            }
            val parser = MjpegParser(boundary)
            val buffer = ByteArray(32 * 1024)
            while (isActive) {
                val read = try {
                    connection.inputStream.read(buffer)
                } catch (e: Exception) {
                    if (!isActive) return@launch
                    break
                }
                if (read < 0) break
                val frames = parser.feed(buffer, 0, read)
                val last = frames.lastOrNull()?.let { decode(it) }
                if (last != null) withContext(Dispatchers.Main) { onFrame(last) }
            }
        } catch (e: Exception) {
            if (!isActive) return@launch
        } finally {
            runCatching { connection?.disconnect() }
        }
        delay(backoff(backoffCount++))
    }
}

private fun backoff(failures: Long): Long = minOf(5_000L shl failures.coerceAtMost(4).toInt(), 60_000L)

private fun fetchFrame(url: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (url == null) return null
    return runCatching {
        val (cleanUrl, auth) = com.samosaai.masala.widgets.StreamAuth.split(url)
        val connection = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            if (auth != null) setRequestProperty("Authorization", auth)
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            decode(bytes)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private fun decode(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
