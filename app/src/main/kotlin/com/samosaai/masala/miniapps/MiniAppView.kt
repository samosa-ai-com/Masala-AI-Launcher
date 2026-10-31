package com.samosaai.masala.miniapps

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.samosaai.masala.R
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.masala
import com.samosaai.masala.ui.skills.ReportDialog
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** Each mini-app gets its own origin under this domain, so `localStorage` can't be shared. */
private const val APP_DOMAIN = "appassets.androidplatform.net"

internal fun originOf(appId: String) = "https://$appId.$APP_DOMAIN/"

/**
 * One generated HTML mini-app on screen (plan Phase 3, Lane D). JavaScript on, no JS bridge,
 * no file or content access, network blocked per app unless the user enables it, and nothing
 * from the host app's own assets is reachable.
 */
@Composable
fun MiniAppView(
    appId: String,
    html: String,
    networkAllowed: Boolean,
    modifier: Modifier = Modifier,
    onWebView: (WebView?) -> Unit = {},
) {
    var webView by remember(appId) { mutableStateOf<WebView?>(null) }
    // The document and network policy are fixed when the page loads (its CSP header and start
    // script), so a new HTML or the switch gives the app a fresh WebView. Its localStorage survives;
    // what it held in memory doesn't. The old one is destroyed in onRelease.
    key(appId, html, networkAllowed) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // Fill the slot: a wrap-content WebView gives the page a collapsed viewport, so
                    // `height: 100%` layouts squash to the top.
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    hostMiniApp(this, appId, html, networkAllowed) { line -> MiniAppConsole.record(appId, line) }
                    webView = this
                    onWebView(this)
                }
            },
            modifier = modifier,
            onRelease = {
                it.destroy()
                // The new WebView may already be in place by the time the old one goes.
                if (webView === it) {
                    webView = null
                    onWebView(null)
                }
            },
        )
    }
}

/**
 * With network off, what a CSP has to stop: `shouldInterceptRequest` never sees WebSocket
 * connections (from the page or its workers), and `shouldOverrideUrlLoading` never sees a form POST.
 * Everything else stays allowed so the app runs as written.
 */
private const val OFFLINE_CSP =
    "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; connect-src 'self' data: blob:; form-action 'self'"

/**
 * With network off, WebRTC goes too: a peer connection reaches STUN/TURN servers outside every
 * request hook and CSP. This runs at document start in every loaded frame, but a new iframe's
 * first `about:blank` document gets no start script, and it comes with its own copy of every global.
 * So each window this runs in also strips its child frames: from a capture `load` listener on its
 * document (an `about:blank` iframe fires `load` while it is being inserted, before any page script
 * can reach it), from the frame getters, and after every DOM call that can insert a frame.
 * The builtins it calls later are captured first, so an app that replaces `forEach`, `apply` or
 * `defineProperty` can't switch it off. `MiniAppNetworkTest` tries each way around this.
 */
private const val NO_WEBRTC = """
(function () {
  var NAMES = ['RTCPeerConnection', 'webkitRTCPeerConnection'];
  var define = Object.defineProperty, describe = Object.getOwnPropertyDescriptor, call = Reflect.apply;
  function each(list, f) { for (var i = 0; i < list.length; i++) f(list[i]); }
  function strip(w) {
    try {
      if (!w || w.__masalaNoRtc) return;
      define(w, '__masalaNoRtc', { value: true });
      each(NAMES, function (n) {
        try { define(w, n, { value: undefined, writable: false, configurable: false }); } catch (e) {}
      });
      guard(w);
    } catch (e) {
      // A cross-origin frame: it loaded a document, so its own start script covered it.
    }
  }
  function sweep(w) {
    try { for (var i = 0; i < w.length; i++) { strip(w[i]); sweep(w[i]); } } catch (e) {}
  }
  function all() {
    var t = window;
    try { if (window.top.length >= 0) t = window.top; } catch (e) {}
    strip(t);
    sweep(t);
  }
  function wrap(proto, name, getter) {
    var d = proto && describe(proto, name);
    if (!d) return;
    if (typeof d.value === 'function') {
      var f = d.value;
      d.value = function () { try { return call(f, this, arguments); } finally { all(); } };
    } else if (getter && d.get) {
      var g = d.get;
      d.get = function () { var r = call(g, this, []); all(); return r; };
    } else if (d.set) {
      var s = d.set;
      d.set = function (v) { try { call(s, this, [v]); } finally { all(); } };
    } else {
      return;
    }
    define(proto, name, d);
  }
  function guard(w) {
    // On the document: a `load` event's path stops there and never reaches the window.
    w.document.addEventListener('load', function (e) {
      try { if (e.target && e.target.contentWindow) strip(e.target.contentWindow); } catch (x) {}
      all();
    }, true);
    var inserts = ['appendChild', 'insertBefore', 'replaceChild', 'append', 'prepend', 'before', 'after',
      'replaceWith', 'replaceChildren', 'insertAdjacentElement', 'insertAdjacentHTML', 'innerHTML', 'outerHTML',
      'setHTMLUnsafe', 'write', 'writeln', 'execCommand', 'insertNode', 'surroundContents'];
    each([w.Node, w.Element, w.Document, w.DocumentFragment, w.ShadowRoot, w.CharacterData, w.DocumentType, w.Range],
      function (C) { if (C) each(inserts, function (n) { wrap(C.prototype, n, false); }); });
    each([w.HTMLIFrameElement, w.HTMLFrameElement, w.HTMLObjectElement, w.HTMLEmbedElement], function (C) {
      if (C) each(['contentWindow', 'contentDocument', 'getSVGDocument'], function (n) { wrap(C.prototype, n, true); });
    });
  }
  strip(window);
})();
"""

/**
 * Sets [wv] up as the host for one app and loads it: the stored [html] is served as the app's own
 * origin, nothing else from the app is reachable, and navigation never leaves the app. Without
 * [networkAllowed] every other request is refused, a CSP stops WebSockets and WebRTC is removed.
 * Shared by [MiniAppView] and [MiniAppProbe], so what the probe checks is what the user runs.
 */
internal fun hostMiniApp(
    wv: WebView,
    appId: String,
    html: String,
    networkAllowed: Boolean,
    onPageFinished: () -> Unit = {},
    onConsole: (String) -> Unit,
) {
    val origin = originOf(appId)
    // Hosts already reported as blocked; requests are intercepted off the main thread.
    val blockedHosts = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    applyMiniAppSettings(wv, networkAllowed)
    var document = html
    if (!networkAllowed) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Every frame: this WebView only ever shows this one app.
            WebViewCompat.addDocumentStartJavaScript(wv, NO_WEBRTC, setOf("*"))
        } else {
            // Older WebView: the main frame only, before any of the app's own script. After the
            // doctype, or the page would render in quirks mode.
            val doctype = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)?.value.orEmpty()
            document = "$doctype<script>$NO_WEBRTC</script>${html.substring(doctype.length)}"
        }
    }
    val htmlBytes = document.toByteArray(Charsets.UTF_8)
    val headers = if (networkAllowed) emptyMap() else mapOf("Content-Security-Policy" to OFFLINE_CSP)
    wv.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            // The document itself, and in-page routing, come from the stored HTML.
            if (url == origin || url.startsWith(origin + "#") || url.startsWith(origin + "?")) {
                return WebResourceResponse(
                    "text/html", "utf-8", 200, "OK", headers,
                    ByteArrayInputStream(htmlBytes),
                )
            }
            // Nothing else is served from the app: no asset directory, no files.
            // With network off, every other request is refused outright.
            if (!networkAllowed) {
                val host = request.url.host.orEmpty()
                if (blockedHosts.add(host)) onConsole("WARNING: blocked a request to $host: internet is off for this app")
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            // Stay inside the mini-app: same-origin navigation is the app's own routing, and the
            // WebView handles it itself (an in-page anchor keeps the page and its state).
            // Leaving the app is never allowed, network on or off.
            return !url.startsWith(origin)
        }

        override fun onPageFinished(view: WebView, url: String) = onPageFinished()
    }
    wv.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            onConsole("${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            return super.onConsoleMessage(message)
        }
    }
    wv.loadUrl(origin)
}

/** In-memory console log per app, newest last (cap 20): what the repair turn reads. */
object MiniAppConsole {
    private val lock = Any()
    private val logs = LinkedHashMap<String, ArrayDeque<String>>()
    val state = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    fun record(appId: String, line: String) {
        synchronized(lock) {
            val q = logs.getOrPut(appId) { ArrayDeque() }
            q.add(line.take(500))
            while (q.size > 20) q.removeAt(0)
            state.value = logs.mapValues { it.value.toList() }
        }
        Log.d("MiniApp", "$appId console: $line")
    }

    /** Every line the app logged, `console.log` included. */
    fun logs(appId: String): List<String> = synchronized(lock) { logs[appId]?.toList().orEmpty() }

    /** Only the lines worth a repair turn: errors and warnings, not the app's own `console.log`. */
    fun errors(appId: String): List<String> = logs(appId).filter { it.startsWith("ERROR") || it.startsWith("WARNING") }

    fun clear(appId: String) {
        synchronized(lock) {
            logs.remove(appId)
            state.value = logs.mapValues { it.value.toList() }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun applyMiniAppSettings(wv: WebView, networkAllowed: Boolean) {
    wv.settings.javaScriptEnabled = true
    // No bridge, no files, no content providers: the app sees only its own origin.
    wv.settings.allowFileAccess = false
    wv.settings.allowContentAccess = false
    wv.settings.allowFileAccessFromFileURLs = false
    wv.settings.allowUniversalAccessFromFileURLs = false
    wv.settings.domStorageEnabled = true
    wv.settings.loadsImagesAutomatically = true
    wv.settings.blockNetworkLoads = !networkAllowed
    wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
}

/** Full-screen host for one mini-app: Back, network toggle, Share, Pin, Report, Delete. */
class MiniAppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val appId = intent.getStringExtra(EXTRA_APP_ID).orEmpty()
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) { Surface(Modifier.fillMaxSize()) { MiniAppScreen(appId, onBack = ::finish) } }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun MiniAppScreen(appId: String, onBack: () -> Unit) {
        val context = LocalContext.current
        val app = context.masala
        val scope = rememberCoroutineScope()
        var title by remember { mutableStateOf("") }
        var html by remember { mutableStateOf<String?>(null) }
        var networkAllowed by remember { mutableStateOf(false) }
        var gone by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        var reporting by remember { mutableStateOf(false) }
        var webView by remember { mutableStateOf<WebView?>(null) }
        LaunchedEffect(appId) {
            val row = withContext(Dispatchers.IO) { app.miniApps.get(appId) }
            if (row == null) {
                gone = true
            } else {
                title = row.title
                html = row.html
                networkAllowed = row.networkAllowed
            }
        }
        // In-app history first, like any browser; the top bar's Back always leaves.
        BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

        // Own scaffold, not SettingsScaffold: the app needs the full remaining height, and a
        // weighted child inside a scrolling column can't be measured.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title.ifEmpty { stringResource(R.string.miniapp_title) }, maxLines = 1) },
                    navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
                    actions = {
                        if (networkAllowed) Text(
                            stringResource(R.string.miniapp_network_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when {
                    gone -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.miniapp_gone), style = MaterialTheme.typography.bodyMedium)
                    }
                    html == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    else -> {
                        val doc = html!!
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.miniapp_network), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(R.string.miniapp_network_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = networkAllowed,
                                onCheckedChange = { on ->
                                    scope.launch {
                                        app.miniApps.setNetworkAllowed(appId, on)
                                        networkAllowed = on
                                    }
                                },
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            MiniAppView(appId, doc, networkAllowed, Modifier.fillMaxSize()) { webView = it }
                        }
                        // Wraps on a narrow screen instead of squeezing the labels.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            TextButton(onClick = {
                                scope.launch {
                                    val file = withContext(Dispatchers.IO) {
                                        java.io.File(cacheDir, "miniapps").apply { mkdirs() }
                                            .resolve("$appId.html").apply { writeText(doc) }
                                    }
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        this@MiniAppActivity, "$packageName.miniapp", file
                                    )
                                    startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/html"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                // The grant only travels with clipData.
                                                clipData = android.content.ClipData.newRawUri(null, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            },
                                            getString(R.string.miniapp_share),
                                        )
                                    )
                                }
                            }) { Text(stringResource(R.string.miniapp_share)) }
                            // Resolved in composition: reading resources off LocalContext inside the
                            // launch below trips lint (LocalContextGetResourceValueCall).
                            val pinTitle = stringResource(R.string.miniapp_title)
                            val pinnedText = stringResource(R.string.miniapp_pinned)
                            val pinFailedText = stringResource(R.string.miniapp_pin_failed)
                            TextButton(onClick = {
                                scope.launch {
                                    val problems = app.layouts.pinMiniApp(
                                        appId, title.ifEmpty { pinTitle }
                                    )
                                    val text = if (problems.isEmpty()) pinnedText
                                    else pinFailedText
                                    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) { Text(stringResource(R.string.miniapp_pin)) }
                            TextButton(onClick = { reporting = true }) { Text(stringResource(R.string.report_action)) }
                            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.settings_version_delete)) }
                        }
                    }
                }
            }
        }
        if (reporting) {
            ReportDialog(
                title = stringResource(R.string.miniapp_report_title),
                onReport = { reasons, note ->
                    // The app's id and title identify it; its HTML stays in the mini-app table.
                    app.aiReports.add("mini_app:$appId", reasons, note, title)
                    reporting = false
                    android.widget.Toast.makeText(context, R.string.report_thanks, android.widget.Toast.LENGTH_SHORT).show()
                },
                onDismiss = { reporting = false },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.miniapp_delete_title)) },
                text = { Text(stringResource(R.string.miniapp_delete_message, title)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            app.deleteMiniApp(appId)
                            onBack()
                        }
                    }) { Text(stringResource(R.string.settings_version_delete)) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_back)) } },
            )
        }
    }

    companion object {
        const val EXTRA_APP_ID = "app_id"
    }
}

/** Opens a mini-app from anywhere an Activity starter is available. */
fun Context.openMiniApp(appId: String) {
    startActivity(
        Intent(this, MiniAppActivity::class.java)
            .putExtra(MiniAppActivity.EXTRA_APP_ID, appId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
