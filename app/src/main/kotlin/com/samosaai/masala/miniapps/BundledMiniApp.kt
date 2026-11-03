package com.samosaai.masala.miniapps

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * One bundled pocket app on screen: the single HTML file from `assets/miniapps/` served through
 * `WebViewAssetLoader`, so `localStorage` gets a stable `https` origin and persists. Same sandbox
 * as [MiniAppView] otherwise: JavaScript on, no file or content access, no network (the pack is
 * offline), the `masala:` media bridge answered only when the user allowed media, and navigation can never
 * leave the pack (only `assets/miniapps/` is served; nothing else from the host app is reachable).
 *
 * Media stays off for bundled apps: there is no Room row, so [MiniAppStore.mediaAllowed] answers
 * false and the bridge returns its JSON denial, exactly like a user app that never opted in.
 */
@Composable
fun BundledMiniAppView(
    app: PocketApp,
    modifier: Modifier = Modifier,
    onWebView: (WebView?) -> Unit = {},
) {
    var webView by remember(app.id) { mutableStateOf<WebView?>(null) }
    key(app.id) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    val masalaApp = ctx.applicationContext as? com.samosaai.masala.MasalaApp
                    hostBundledMiniApp(this, app, masalaApp) { line -> MiniAppConsole.record(app.id, line) }
                    webView = this
                    onWebView(this)
                }
            },
            modifier = modifier,
            onRelease = {
                it.destroy()
                if (webView === it) {
                    webView = null
                    onWebView(null)
                }
            },
        )
    }
}

/** 403 for anything the offline pack must not reach, with the console note the repair turn reads. */
internal fun blockedPocketResponse(host: String, onConsole: (String) -> Unit): WebResourceResponse {
    if (host.isNotEmpty()) onConsole("WARNING: blocked a request to $host: internet is off for this app")
    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
}

@SuppressLint("SetJavaScriptEnabled")
internal fun hostBundledMiniApp(
    wv: WebView,
    app: PocketApp,
    masalaApp: com.samosaai.masala.MasalaApp?,
    onConsole: (String) -> Unit,
) {
    applyMiniAppSettings(wv, networkAllowed = false)
    // Served from this app's own origin only: the loader answers for its domain, and the
    // interceptor below refuses anything else, so apps can't read each other's storage.
    val domain = "${PocketStoreLogic.domainFor(app)}.${PocketStoreLogic.APP_DOMAIN}"
    val loader = WebViewAssetLoader.Builder()
        .setDomain(domain)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(wv.context))
        .build()
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        WebViewCompat.addDocumentStartJavaScript(wv, miniAppNoWebRtc, setOf("*"))
    }
    wv.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            // Mediated media bridge first, same rules as a user app: denied unless allowed.
            if (url.startsWith("${MiniAppMediaBridge.SCHEME}://${MiniAppMediaBridge.HOST}/")) {
                if (masalaApp == null) return MiniAppMediaBridge.probeDenial()
                return runCatching { MiniAppMediaBridge.intercept(masalaApp, app.id, url) }.getOrNull()
            }
            // This app's origin and the pack only: any other host or path is refused.
            if (request.url.host != domain || !PocketStoreLogic.isServable(url)) {
                val host = request.url.host.orEmpty().ifEmpty { url.take(80) }
                return blockedPocketResponse(host, onConsole)
            }
            val served = loader.shouldInterceptRequest(request.url) ?: return blockedPocketResponse("", onConsole)
            // Served as-is but with the offline CSP, so WebSockets can't reach past the interceptor.
            val headers = mapOf("Content-Security-Policy" to miniAppOfflineCsp)
            // The asset loader's response carries no HTTP status (0), and WebResourceResponse
            // rejects anything below 100; the three-argument form means "200 OK".
            return if (served.statusCode >= 100) {
                WebResourceResponse(served.mimeType, served.encoding, served.statusCode, served.reasonPhrase, headers, served.data)
            } else {
                WebResourceResponse(served.mimeType, served.encoding, served.data).apply {
                    responseHeaders = headers
                }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Stay inside the pack; leaving it is never allowed.
            return !PocketStoreLogic.isServable(request.url.toString())
        }
    }
    wv.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            onConsole("${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            return super.onConsoleMessage(message)
        }
    }
    wv.loadUrl(PocketStoreLogic.assetUrl(app))
}
