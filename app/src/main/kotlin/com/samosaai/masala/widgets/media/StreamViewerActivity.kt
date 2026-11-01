package com.samosaai.masala.widgets.media

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.samosaai.masala.masala
import com.samosaai.masala.ui.theme.MasalaTheme
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * One stream full-screen (plan `docs/plans/widget-maker.md` §4.3): plays with audio, a mute
 * toggle, pinch-zoom, a snapshot-to-file button when the source has a snapshot URL, and a close
 * button. Back closes. Not exported; home opens it from a video tap.
 */
class StreamViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID) ?: run { finish(); return }
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(com.samosaai.masala.data.LauncherSettings())
            MasalaTheme(settings) {
                Surface(Modifier.fillMaxSize()) {
                    Viewer(sourceId, onClose = ::finish)
                }
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE_ID = "com.samosaai.masala.STREAM_SOURCE_ID"
    }
}

@Composable
private fun Viewer(sourceId: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.masala
    val scope = rememberCoroutineScope()
    val source = app.streamSources.current()[sourceId]
    val streamUrl = remember(sourceId) { app.streamSources.urlWithCredentials(sourceId) }
    var muted by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(sourceId, streamUrl) {
        val url = streamUrl
        if (source == null || url == null) {
            onClose()
            return@DisposableEffect onDispose { }
        }
        val created = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
        player = created
        onDispose {
            player = null
            runCatching { created.release() }
        }
    }
    val current = player
    Box(
        Modifier.fillMaxSize().systemBarsPadding()
            .pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 4f) } },
    ) {
        if (current != null) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply { useController = false }
                },
                update = { it.player = current },
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale),
            )
        } else {
            Text("…", modifier = Modifier.align(Alignment.Center))
        }
        Row(Modifier.align(Alignment.TopCenter).padding(8.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = null) }
            IconButton(onClick = {
                muted = !muted
                current?.volume = if (muted) 0f else 1f
            }) { Icon(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, contentDescription = null) }
            val snapshotUrl = remember(sourceId) { app.streamSources.snapshotUrlWithCredentials(sourceId) }
            if (snapshotUrl != null) {
                TextButton(onClick = {
                    scope.launch { saveSnapshot(context, source?.name ?: sourceId, snapshotUrl) }
                }) { Text("Snapshot") }
            }
        }
        if (scale > 1f) {
            TextButton(onClick = { scale = 1f }, modifier = Modifier.align(Alignment.BottomCenter)) { Text("Reset zoom") }
        }
    }
}

private suspend fun saveSnapshot(context: android.content.Context, name: String, url: String) {
    val toast = { text: String -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            try {
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                val bytes = connection.inputStream.use { it.readBytes() }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name-${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("No slot")
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("No stream")
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } finally {
                connection.disconnect()
            }
        }.onSuccess { toast("Saved") }.onFailure { toast("Couldn't save: ${(it.message ?: "?").take(80)}") }
    }
}
