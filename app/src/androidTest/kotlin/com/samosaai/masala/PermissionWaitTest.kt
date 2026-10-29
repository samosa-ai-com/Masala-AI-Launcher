package com.samosaai.masala

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.FunctionTool
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.permissions.CapabilityPrompt
import com.samosaai.masala.tools.ActionLog
import com.samosaai.masala.files.AppStorageFileAccess
import com.samosaai.masala.tools.MasalaToolExecutor
import com.samosaai.masala.tools.MasalaToolRegistry
import com.samosaai.masala.tools.ToolGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tool that discovers a missing permission must wait for the user and then finish, instead of
 * handing "not granted" back to the model while the dialog is still on screen.
 */
@RunWith(AndroidJUnit4::class)
class PermissionWaitTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()

    private fun executor(prompt: CapabilityPrompt, tool: FunctionTool): MasalaToolExecutor {
        val registry = MasalaToolRegistry(AppStorageFileAccess(app))
        registry.register(tool)
        return MasalaToolExecutor(app, registry, ActionLog(app), ToolGate { _, _ -> null }, prompt)
    }

    private fun flakyTool(calls: IntArray, permission: String = "android.permission.RECORD_AUDIO"): FunctionTool {
        val def = ToolDefinition("flaky", "test tool", buildJsonObject { put("type", "object") })
        return FunctionTool(def, ToolSensitivity.READ_ONLY) {
            calls[0] += 1
            if (calls[0] == 1) ActionResult.permissionNeeded(permission, "needs $permission")
            else ActionResult.ok("done after the grant")
        }
    }

    @Test
    fun `the tool waits for the answer and then completes`() = runBlocking {
        val calls = intArrayOf(0)
        val asked = mutableListOf<String>()
        // Grant RECORD_AUDIO first so the "phone really holds it" check passes after the answer.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, "android.permission.RECORD_AUDIO")
        val prompt = CapabilityPrompt { marker ->
            asked += marker
            true
        }
        val result = executor(prompt, flakyTool(calls)).execute("flaky", JsonObject(emptyMap()))
        assertTrue("expected success, got $result", result is com.samosaai.masala.core.tool.ToolResult.Success)
        assertEquals(listOf("android.permission.RECORD_AUDIO"), asked)
        assertEquals(2, calls[0])
    }

    @Test
    fun `a denied answer leaves the tool reporting what it needs`() = runBlocking {
        val calls = intArrayOf(0)
        val prompt = CapabilityPrompt { false }
        val result = executor(prompt, flakyTool(calls)).execute("flaky", JsonObject(emptyMap()))
        assertTrue("expected NeedsCapability, got $result", result is com.samosaai.masala.core.tool.ToolResult.NeedsCapability)
        assertEquals(1, calls[0])
    }
}
