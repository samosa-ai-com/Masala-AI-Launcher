package com.samosaai.masala.miniapps

import android.net.Uri
import android.webkit.WebResourceResponse
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.tools.EditImageTool
import com.samosaai.masala.tools.GenerateImageTool
import com.samosaai.masala.tools.SamSegmentTool
import com.samosaai.masala.tools.SpeakTool
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Mediated media bridge for mini-apps (plan `docs/plans/media-tools.md`).
 *
 * Mini-app JS has no keys and no network to the model endpoints. When Masala
 * allows it for an app ([MiniAppStore.mediaAllowed]), the app can call:
 * - `fetch('masala://media/generate?prompt=..&style=..')` → `{ok, ref, dataUrl, message}`
 * - `fetch('masala://media/segment?ref=..&prompt=..')` → `{ok, ref, dataUrl, message}`
 * - `fetch('masala://media/speak?text=..')` → `{ok, message}` (plays on host speakers)
 * - `fetch('masala://media/capabilities')` → `{image,edit,segment,speak,transcribe}`
 *
 * Those URLs are rewritten in the page to the same-origin [PATH] (see [miniAppMediaShim]),
 * because WebView's fetch never lets a custom scheme reach the interceptor.
 * The host proxies through the same tools chat uses ([GenerateImageTool],
 * [SamSegmentTool], [SpeakTool]); the API key never enters the JS context.
 * [EditImageTool] is intentionally not exposed yet: mini-apps hold no files to
 * edit except generated refs, and `segment` covers the read path.
 */
object MiniAppMediaBridge {
    const val SCHEME = "masala"
    const val HOST = "media"

    /**
     * What the pre-answer probe answers: valid JSON saying media isn't available in
     * previews, so the page loads with no console errors and the repair turn doesn't
     * strip working media calls. Real runs go through [intercept].
     */
    fun probeDenial(): WebResourceResponse =
        json(200, """{"ok":false,"error":"media not available in preview"}""")

    /**
     * WebView's `fetch` and XHR refuse custom schemes before `shouldInterceptRequest` runs
     * ("URL scheme "masala" is not supported"), so [miniAppMediaShim] rewrites `masala://media/<op>`
     * to this same-origin path, which the host intercepts like the documented form.
     */
    const val PATH = "/__masala/media/"

    /** True for a bridge call: the documented `masala://media/...` form, or [PATH] on the app's own [host]. */
    fun isBridgeUrl(url: String, host: String): Boolean {
        if (url.startsWith("$SCHEME://$HOST/")) return true
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.host == host && uri.path.orEmpty().startsWith(PATH)
    }

    fun intercept(app: MasalaApp, appId: String, url: String): WebResourceResponse? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val op = when {
            uri.scheme == SCHEME && uri.host == HOST -> uri.pathSegments.firstOrNull().orEmpty()
            uri.path.orEmpty().startsWith(PATH) -> uri.path.orEmpty().removePrefix(PATH).substringBefore('/')
            else -> return null
        }
        val allowed = runBlocking { runCatching { app.miniApps.mediaAllowed(appId) }.getOrDefault(false) }
        if (!allowed) return json(403, """{"ok":false,"error":"media not allowed for this app"}""")
        return when (op) {
            "capabilities" -> json(200, """{"ok":true,"image":true,"edit":false,"segment":true,"speak":true,"transcribe":false}""")
            "generate" -> generate(app, uri.getQueryParameter("prompt").orEmpty(), uri.getQueryParameter("style"))
            "segment" -> segment(app, uri.getQueryParameter("ref").orEmpty(), uri.getQueryParameter("prompt").orEmpty())
            "speak" -> speak(app, uri.getQueryParameter("text").orEmpty())
            else -> json(404, """{"ok":false,"error":"unknown media op '$op'"}""")
        }
    }

    private fun generate(app: MasalaApp, prompt: String, style: String?): WebResourceResponse {
        if (prompt.isBlank()) return json(400, """{"ok":false,"error":"prompt required"}""")
        val result = runBlocking {
            withTimeoutOrNull(240_000) {
                GenerateImageTool(app, app.aiSettings).generate(prompt, style)
            }
        } ?: return json(504, """{"ok":false,"error":"timed out"}""")
        if (!result.success) return json(502, """{"ok":false,"error":${jsonStr(result.message.take(300))}}""")
        val ref = result.produced.firstOrNull()?.ref.orEmpty()
        val dataUrl = runCatching { thumbDataUrl(ref) }.getOrNull().orEmpty()
        return json(200, """{"ok":true,"ref":${jsonStr(ref)},"dataUrl":${jsonStr(dataUrl)},"message":${jsonStr(result.message.take(300))}}""")
    }

    private fun segment(app: MasalaApp, ref: String, prompt: String): WebResourceResponse {
        if (ref.isBlank() || prompt.isBlank()) return json(400, """{"ok":false,"error":"ref and prompt required"}""")
        if (!isAppFile(app, ref)) return json(403, """{"ok":false,"error":"ref not found"}""")
        val result = runBlocking {
            withTimeoutOrNull(240_000) {
                SamSegmentTool(app, app.aiSettings, appFiles(app)).segment(ref, prompt)
            }
        } ?: return json(504, """{"ok":false,"error":"timed out"}""")
        if (!result.success) return json(502, """{"ok":false,"error":${jsonStr(result.message.take(300))}}""")
        val annotated = result.produced.firstOrNull { it.kind == "segmentation" }?.ref.orEmpty()
        val dataUrl = runCatching { thumbDataUrl(annotated) }.getOrNull().orEmpty()
        return json(200, """{"ok":true,"ref":${jsonStr(annotated)},"dataUrl":${jsonStr(dataUrl)},"message":${jsonStr(result.message.take(500))}}""")
    }

    private fun speak(app: MasalaApp, text: String): WebResourceResponse {
        if (text.isBlank()) return json(400, """{"ok":false,"error":"text required"}""")
        if (text.length > SpeakTool.MAX_CHARS) return json(400, """{"ok":false,"error":"text too long"}""")
        val result = runBlocking {
            withTimeoutOrNull(120_000) {
                SpeakTool(app, app.aiSettings, appTts(app)).speak(text)
            }
        } ?: return json(504, """{"ok":false,"error":"timed out"}""")
        return if (result.success) json(200, """{"ok":true,"message":${jsonStr(result.message.take(300))}}""")
        else json(502, """{"ok":false,"error":${jsonStr(result.message.take(300))}}""")
    }

    private fun appFiles(app: MasalaApp): ToolFiles =
        ToolFiles { com.samosaai.masala.files.AppStorageFileAccess(app) }
    private fun appTts(app: MasalaApp): com.samosaai.masala.audio.TtsEngine = app.ttsEngine()

    private fun isAppFile(app: MasalaApp, ref: String): Boolean {
        val f = File(ref)
        val base = app.filesDir.canonicalPath
        return runCatching { f.canonicalPath.startsWith(base) && f.exists() }.getOrDefault(false)
    }

    private fun thumbDataUrl(path: String): String {
        val f = File(path)
        if (!f.exists()) return ""
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth <= 0) return ""
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 768 || opts.outHeight / (sample * 2) >= 768) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return ""
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, bos)
        if (!bmp.isRecycled()) bmp.recycle()
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun json(status: Int, body: String): WebResourceResponse =
        WebResourceResponse("application/json", "utf-8", status, statusText(status), emptyMap(), ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        502 -> "Bad Gateway"
        504 -> "Gateway Timeout"
        else -> "Error"
    }

    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
        append('"')
    }
}
