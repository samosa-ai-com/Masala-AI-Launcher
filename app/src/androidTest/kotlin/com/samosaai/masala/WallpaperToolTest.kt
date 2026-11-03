package com.samosaai.masala

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.tools.ActionLog
import com.samosaai.masala.tools.MasalaToolExecutor
import com.samosaai.masala.tools.ToolGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The phone failure was a local file the tool refused. These cover the inputs the tool now claims
 * to accept, including a photo-shaped image and an oversized one.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperToolTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun executor(): MasalaToolExecutor {
        val gate = ToolGate { _, _ -> null }
        return MasalaToolExecutor(app, app.tools, ActionLog(app), gate)
    }

    private fun writeImage(name: String, width: Int, height: Int): File {
        val dir = File(app.cacheDir, "wallpaper-tests").apply { mkdirs() }
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 120, 200))
        // A darker stripe at the edges, so the edge-colour fill has something to match.
        for (x in 0 until width) {
            bitmap.setPixel(x, 0, Color.rgb(5, 5, 5))
            bitmap.setPixel(x, height - 1, Color.rgb(5, 5, 5))
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun apply(args: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ToolResult = runBlocking {
        executor().execute("set_wallpaper", buildJsonObject(args))
    }

    @Test
    fun localFileWorks() {
        val file = writeImage("portrait.png", 900, 1600)
        val result = apply { put("url", "file://${file.absolutePath}") }
        println("FOLLOWUP|wallpaper-file|${result::class.simpleName}|${(result as? ToolResult.Success)?.output ?: (result as? ToolResult.Failure)?.message}")
        assertTrue("local file should work: $result", result is ToolResult.Success)
    }

    @Test
    fun bareSdcardPathWorks() {
        // Copy into shared storage so the /sdcard spelling is exercised, as the model sends it.
        val file = writeImage("shared.png", 600, 1200)
        val target = File("/sdcard/Download/masala-wallpaper.png")
        runCatching { file.copyTo(target, overwrite = true) }
        val path = if (target.exists()) "/sdcard/Download/masala-wallpaper.png" else file.absolutePath
        val result = apply { put("url", path) }
        println("FOLLOWUP|wallpaper-path|${result::class.simpleName}|$path")
        assertTrue("bare path should work: $result", result is ToolResult.Success)
        runCatching { target.delete() }
    }

    @Test
    fun oversizedImageIsHandled() {
        val file = writeImage("huge.png", 4032, 6048)
        val result = apply { put("url", "file://${file.absolutePath}") }
        println("FOLLOWUP|wallpaper-huge|${result::class.simpleName}")
        assertTrue("a 24 MP image must be handled, not crash: $result", result is ToolResult.Success)
    }

    @Test
    fun lockScreenTargetWorks() {
        val file = writeImage("lock.png", 800, 800)
        val result = apply {
            put("url", "file://${file.absolutePath}")
            put("target", "lock")
        }
        println("FOLLOWUP|wallpaper-lock|${result::class.simpleName}|${(result as? ToolResult.Success)?.output}")
        assertTrue("lock target should work: $result", result is ToolResult.Success)
    }
}
