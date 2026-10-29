package com.samosaai.masala.miniapps

import android.content.Context
import android.webkit.WebStorage
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs new mini-app HTML once, off screen, before the tool answers, so the model sees the app's
 * console errors and can fix them in the same run (plan Phase 3 Lane D: "console errors captured for
 * the repair turn"). Same host setup as [MiniAppView], network always off, and its own origin so the
 * real app's `localStorage` is never touched; that origin's storage is wiped afterwards.
 *
 * The WebView isn't on screen, so frame callbacks may not run: this catches errors while the page
 * loads and in its first timers, not ones that only happen during play.
 */
class MiniAppProbe(private val context: Context) {
    private val mutex = Mutex()

    /** Console error lines from loading [html], or null when it couldn't be checked. */
    suspend fun errors(html: String, settleMillis: Long = SETTLE_MILLIS): List<String>? = mutex.withLock {
        withContext(Dispatchers.Main) {
            val lines = mutableListOf<String>()
            val finished = CompletableDeferred<Unit>()
            val webView = runCatching { WebView(context) }.getOrNull() ?: return@withContext null
            try {
                // A fixed size, as if on screen, so layout code that reads the viewport runs as it would.
                webView.layout(0, 0, 1080, 1920)
                hostMiniApp(webView, PROBE_ID, html, AtomicBoolean(false), onPageFinished = { finished.complete(Unit) }) { line ->
                    if (line.startsWith("ERROR")) lines += line.take(500)
                }
                withTimeoutOrNull(LOAD_TIMEOUT_MILLIS) { finished.await() } ?: return@withContext null
                delay(settleMillis)
                lines.distinct().take(MAX_LINES)
            } finally {
                webView.stopLoading()
                webView.destroy()
                WebStorage.getInstance().deleteOrigin(originOf(PROBE_ID).trimEnd('/'))
            }
        }
    }

    companion object {
        private const val PROBE_ID = "probe"
        const val SETTLE_MILLIS = 1_500L
        private const val LOAD_TIMEOUT_MILLIS = 8_000L
        private const val MAX_LINES = 10
    }
}
