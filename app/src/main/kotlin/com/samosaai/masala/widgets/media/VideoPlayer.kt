package com.samosaai.masala.widgets.media

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.widgets.StreamKind
import com.samosaai.masala.widgets.StreamSourceStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive


/**
 * What live video on home needs beyond the widget itself:
 * the 2-player pool, whether home is showing (resumed and the drawer shut), and where a tap goes.
 */
data class MediaHost(
    val pool: PlayerPool,
    val homeVisible: () -> Boolean = { true },
    val onFullscreen: (sourceId: String) -> Unit = { },
)

/**
 * A live player-kind stream on home (plan §4.3): plays muted while home shows its page, pauses
 * behind the drawer, on another home page, or after 5 idle minutes (tap resumes), falls back to
 * snapshot mode when both pool slots are held — with a Live button that steals the stalest slot.
 * Errors stay inline with a retry; nothing ever crash-loops.
 */
@Composable
fun LiveVideo(sourceId: String, store: StreamSourceStore, media: MediaHost, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val context = LocalContext.current
    val source = store.current()[sourceId]
    if (source == null) {
        Text(stringResource(R.string.widgets_video_no_source), style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium)
        return
    }
    val streamUrl = remember(sourceId) { store.urlWithCredentials(sourceId) }
    if (streamUrl == null) {
        Text(stringResource(R.string.widgets_source_login_gone), style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium)
        return
    }
    val snapshotUrl = remember(sourceId) { store.snapshotUrlWithCredentials(sourceId) }
    var forced by remember(sourceId) { mutableStateOf(false) }
    // Metered and off-LAN streams start in snapshot mode unless the user allowed mobile data.
    var liveOk by remember(sourceId) {
        mutableStateOf(forced || !context.isMeteredNow() || isLanUrl(streamUrl) || store.isMeteredAllowed(sourceId))
    }
    var stolen by remember(sourceId) { mutableStateOf(false) }
    if (!liveOk && snapshotUrl != null && !forced) {
        Column(modifier.fillMaxWidth()) {
            SnapshotImage(sourceId, 10, store, enabled = enabled)
            TextButton(onClick = { forced = true; liveOk = true }) { Text(stringResource(R.string.widgets_video_live)) }
        }
        return
    }
    if (!liveOk && !forced) {
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.widgets_video_metered), style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium)
            TextButton(onClick = { forced = true; liveOk = true }) { Text(stringResource(R.string.widgets_video_live)) }
        }
        return
    }
    PlayerBody(sourceId, streamUrl, source.transport, media, forced || stolen, onSteal = { stolen = true }, modifier, enabled)
}

@Composable
private fun PlayerBody(
    sourceId: String,
    streamUrl: String,
    transport: String?,
    media: MediaHost,
    steal: Boolean,
    onSteal: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var exo by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var failed by remember(sourceId) { mutableStateOf(false) }
    var pausedIdle by remember(sourceId) { mutableStateOf(false) }
    var poolFull by remember(sourceId) { mutableStateOf(false) }
    var retryNonce by remember(sourceId) { mutableStateOf(0) }
    // Play only while resumed and while this page is showing; leaving either releases the pool
    // slot through the finally below, so an off-screen widget holds no player and no connection.
    // The drawer gate and the idle timer tick below.
    LaunchedEffect(sourceId, streamUrl, steal, retryNonce, enabled) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val got = if (steal) media.pool.steal(streamUrl, transport) else media.pool.acquire(streamUrl, transport)
            if (got == null) {
                poolFull = true
                return@repeatOnLifecycle
            }
            poolFull = false
            exo = got
            try {
                val idleAt = System.currentTimeMillis() + IDLE_MS
                while (isActive) {
                    val show = media.homeVisible()
                    got.playWhenReady = show && !pausedIdle && !media.pool.backgrounded
                    if (System.currentTimeMillis() > idleAt && !pausedIdle) {
                        pausedIdle = true
                        got.playWhenReady = false
                    }
                    delay(2_000)
                }
            } finally {
                exo = null
                media.pool.release(streamUrl)
            }
        }
    }
    DisposableEffect(sourceId, streamUrl) {
        onDispose {
            exo?.let { media.pool.release(streamUrl) }
            exo = null
        }
    }
    val current = exo
    // A tap opens the full-screen viewer; the idle overlay carries its own resume button.
    Box(modifier.fillMaxWidth().heightIn(min = 120.dp).clickable(role = Role.Button) { media.onFullscreen(sourceId) }) {
        when {
            poolFull -> {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.widgets_video_pool_full), style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium)
                    TextButton(onClick = onSteal) { Text(stringResource(R.string.widgets_video_live)) }
                }
            }
            failed -> {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.widgets_video_failed), style = MaterialTheme.typography.bodyMedium, color = MasalaTheme.tokens.onWallpaperMedium)
                    TextButton(onClick = { failed = false; pausedIdle = false; retryNonce++ }) { Text(stringResource(R.string.widgets_retry)) }
                }
            }
            current != null -> {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        androidx.media3.ui.PlayerView(ctx).apply {
                            useController = false
                        }
                    },
                    update = { it.player = current },
                    modifier = Modifier.matchParentSize(),
                )
                if (pausedIdle) {
                    TextButton(onClick = { media.pool.touch(streamUrl); pausedIdle = false }, modifier = Modifier.align(Alignment.BottomCenter)) {
                        Text(stringResource(R.string.widgets_video_paused))
                    }
                }
            }
            else -> Text(
                stringResource(R.string.widgets_snapshot_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MasalaTheme.tokens.onWallpaperMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
    // Surface errors surface as playback state, not a crash: flag once, Retry re-acquires.
    LaunchedEffect(current) {
        val p = current ?: return@LaunchedEffect
        while (isActive) {
            if (p.playerError != null) {
                failed = true
                return@LaunchedEffect
            }
            delay(2_000)
        }
    }
}

/** Opens the full-screen viewer for [sourceId]. Home wires this to `StreamViewerActivity`. */
fun Context.openStreamViewer(sourceId: String) {
    startActivity(
        Intent(this, StreamViewerActivity::class.java)
            .putExtra(StreamViewerActivity.EXTRA_SOURCE_ID, sourceId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private const val IDLE_MS = 5 * 60 * 1000L
