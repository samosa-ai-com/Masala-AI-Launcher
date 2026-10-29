package com.samosaai.masala

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/**
 * `set_wallpaper` used to take the app down with an OutOfMemoryError. It must now always come
 * back with a result — success, or a message — and never kill the process.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperToolTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()

    @Test
    fun settingAWallpaperNeverCrashesTheApp() = runBlocking {
        val executor = MasalaToolExecutor(app, app.tools, ActionLog(app), ToolGate { _, _ -> null })
        // A deliberately large image, the shape that used to exhaust memory.
        val result = executor.execute(
            "set_wallpaper",
            buildJsonObject { put("url", "https://picsum.photos/2160/3840") },
        )
        val detail = when (result) {
            is ToolResult.Success -> result.output
            is ToolResult.Failure -> result.message
            is ToolResult.NeedsCapability -> "needs ${result.capability}"
        }
        println("FOLLOWUP|wallpaper|${result::class.simpleName}|$detail")
        // Either it worked, or it explained itself. A process death would fail the run outright.
        assertTrue("unexpected empty result", detail.isNotBlank())
    }
}
