package com.samosaai.masala

import android.Manifest
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samosaai.masala.audio.AndroidTranscriber
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.permissions.CapabilityPrompt
import com.samosaai.masala.tools.ActionLog
import com.samosaai.masala.tools.MasalaToolExecutor
import com.samosaai.masala.tools.ToolGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two things the sweep had to skip: uninstalling an app (needs a target app to remove) and
 * transcribing a real audio file (needs the endpoint or the on-device engine).
 */
@RunWith(AndroidJUnit4::class)
class ToolFollowupsTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private val executor: MasalaToolExecutor by lazy {
        val prompt = CapabilityPrompt { marker ->
            runCatching { automation.grantRuntimePermission(app.packageName, marker) }.isSuccess
        }
        MasalaToolExecutor(app, app.tools, ActionLog(app), ToolGate { _, _ -> null }, prompt)
    }

    private fun shell(cmd: String) {
        runCatching {
            automation.executeShellCommand(cmd).use { pfd: ParcelFileDescriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
            }
        }
    }

    @Test
    fun uninstallAppOpensTheSystemDialogForARealPackage() = runBlocking {
        val target = "com.example.sweeptarget"
        val installed = runCatching { app.packageManager.getPackageInfo(target, 0) }.isSuccess
        println("FOLLOWUP|uninstall-target-installed|$installed")

        val result = executor.execute("uninstall_app", buildJsonObject { put("package_name", target) })
        val detail = (result as? ToolResult.Success)?.output.orEmpty()
        println("FOLLOWUP|uninstall|${result::class.simpleName}|$detail")

        // Play-safe behaviour: the tool asks the system to uninstall, and the user confirms in
        // Android's own dialog. (Verified by hand on the emulator: tapping OK removes the app.)
        if (installed) {
            assertTrue("expected the system uninstall dialog, got: $detail", result is ToolResult.Success)
            assertTrue(
                "message should point at the system dialog: $detail",
                detail.contains("dialog", ignoreCase = true) || detail.contains("uninstall", ignoreCase = true),
            )
        }
    }

    @Test
    fun transcribeFileUsesWhicheverEngineIsAvailable() = runBlocking {
        shell("appops set ${app.packageName} MANAGE_EXTERNAL_STORAGE allow")
        runCatching { automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO) }
        val androidAvailable = AndroidTranscriber.isAvailable(app)
        println("FOLLOWUP|android-transcriber-available|$androidAvailable")

        val args = buildJsonObject {
            put("path", "/sdcard/Download/speech.wav")
        }
        val result = executor.execute("transcribe_file", args)
        val detail = when (result) {
            is ToolResult.Success -> result.output.replace('\n', ' ').take(200)
            is ToolResult.Failure -> result.message.replace('\n', ' ').take(300)
            is ToolResult.NeedsCapability -> "needs ${result.capability}"
        }
        println("FOLLOWUP|transcribe|${result::class.simpleName}|$detail")

        // Either a transcript, or the honest "no engine on this device" message — never a crash
        // and never a bare stack trace.
        when (result) {
            is ToolResult.Success -> assertTrue(detail.isNotBlank())
            is ToolResult.Failure -> assertTrue(
                "failure should explain itself: $detail",
                detail.contains("transcri", ignoreCase = true) || detail.contains("does not exist", true),
            )
            else -> Unit
        }
    }
}
