package com.samosaai.masala.core

import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.core.tool.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActionResultTest {
    @Test
    fun `a success keeps what the tool produced`() {
        val app = Produced("mini_app", "Snake", "ab12cd34")
        val result = assertIs<ToolResult.Success>(ActionResult.ok("Created.", listOf(app)).toToolResult())
        assertEquals("Created.", result.output)
        assertEquals(listOf(app), result.produced)
    }

    @Test
    fun `a missing permission wins over anything produced`() {
        val result = ActionResult(false, "Grant it", needsPermission = "android.permission.CAMERA",
            produced = listOf(Produced("file", "a", "/a"))).toToolResult()
        assertIs<ToolResult.NeedsCapability>(result)
    }
}
