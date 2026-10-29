package com.samosaai.masala

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.connectors.mcp.McpServerStore
import com.samosaai.masala.connectors.mcp.McpStrings
import com.samosaai.masala.connectors.mcp.PrefsMcpStorage
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.tools.ActionLog
import com.samosaai.masala.tools.MasalaToolExecutor
import com.samosaai.masala.tools.ToolGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F8 against the real markitdown MCP server on the host (F8): the emulator reaches
 * it at 10.0.0.2-style alias 10.0.2.2, the phone at the machine's LAN IP. Same URL
 * shape the user types in Settings → Connected tools. This also proves cleartext
 * http to a LAN host passes the platform policy on this build. Skips when the host
 * server isn't running; no secret ever crosses the wire in this test.
 */
@RunWith(AndroidJUnit4::class)
class McpLanTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()

    private fun hostReachable(): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("10.0.2.2", 8765), 2000) }
        true
    }.getOrDefault(false)

    @Before fun clean() {
        PrefsMcpStorage(app).save(null)
        app.tools.setDynamic("mcp", emptyList())
    }

    @After fun cleanUp() {
        runCatching {
            PrefsMcpStorage(app).save(null)
            app.tools.setDynamic("mcp", emptyList())
        }
    }

    @Test fun probeSaveAndCallTheHostMarkitdown() = runBlocking {
        assumeTrue("host markitdown server not running", hostReachable())
        val strings = McpStrings { id, args -> app.getString(id, *args) }
        val store = McpServerStore(PrefsMcpStorage(app), app.tools::setDynamic)
        // The host's markitdown MCP (streamable HTTP). No token: nothing secret crosses.
        val url = "http://10.0.2.2:8765/mcp/"
        try {
            val (ok, message) = store.save(null, "markitdown", url, "", emptyMap(), strings)
            assertTrue("save/probe failed: $message", ok)
            val saved = store.servers.value.single()
            assertTrue(saved.token.isEmpty() && saved.headers.isEmpty())

            val names = app.tools.tools.map { it.definition.name }.filter { it.startsWith("mcp_markitdown__") }
            assertEquals(1, names.size)
            val toolName = names.single()
            assertTrue(toolName.contains("markdown"))

            val gate = ToolGate { _, _ -> null }
            val executor = MasalaToolExecutor(app, app.tools, ActionLog(app), gate)
            val result = executor.execute(toolName, buildJsonObject { put("uri", "https://example.com") })
            assertTrue("unexpected: $result", result is ToolResult.Success)
            assertTrue((result as ToolResult.Success).output.isNotBlank())
        } finally {
            store.servers.value.forEach { store.remove(it.id) }
        }
        assertTrue(app.tools.tools.none { it.definition.name.startsWith("mcp_markitdown__") })
    }
}
