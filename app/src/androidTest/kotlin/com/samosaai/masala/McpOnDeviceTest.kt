package com.samosaai.masala

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.connectors.mcp.McpServerStore
import com.samosaai.masala.connectors.mcp.McpStrings
import com.samosaai.masala.connectors.mcp.PrefsMcpStorage
import com.samosaai.masala.core.tool.ToolOrigin
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.tools.ActionLog
import com.samosaai.masala.tools.MasalaToolExecutor
import com.samosaai.masala.tools.ToolGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MCP through the real on-device path (F8): real [PrefsMcpStorage], the real tool
 * registry's dynamic `mcp` owner, and the real [MasalaToolExecutor] gate — against a
 * loopback MCP server speaking streamable HTTP. The JVM [McpTest] covers the client
 * with a fake store; this proves saving, gating, calling and persistence on Android.
 */
@RunWith(AndroidJUnit4::class)
class McpOnDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private lateinit var server: MockWebServer

    private val toolsJson = """[
        {"name":"search","description":"Search things","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}} ,"annotations":{"readOnlyHint":true}},
        {"name":"post note","description":"Write","inputSchema":{"type":"object"}}
    ]"""

    @Before fun start() {
        // A crashed earlier run must not leak servers into this one.
        PrefsMcpStorage(app).save(null)
        app.tools.setDynamic("mcp", emptyList())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val id = body["id"]?.toString()
                val method = body["method"]!!.jsonPrimitive.content
                if (id == null) return MockResponse().setResponseCode(202)
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-06-18","serverInfo":{"name":"Notes","version":"1"},"capabilities":{}}"""
                    "tools/list" -> """{"tools":$toolsJson}"""
                    "tools/call" -> """{"content":[{"type":"text","text":"called ${body["params"]!!.jsonObject["name"]!!.jsonPrimitive.content}"}]}"""
                    else -> "{}"
                }
                val rpc = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
                return MockResponse()
                    .addHeader("Mcp-Session-Id", "sess-1")
                    .addHeader("Content-Type", "application/json")
                    .setBody(rpc)
            }
        }
        server.start()
    }

    @After fun stop() {
        runCatching {
            PrefsMcpStorage(app).save(null)
            app.tools.setDynamic("mcp", emptyList())
        }
        server.shutdown()
    }

    @Test fun saveGateCallAndPersistenceUseTheRealStack() = runBlocking {
        val strings = McpStrings { id, args -> app.getString(id, *args) }
        val store = McpServerStore(PrefsMcpStorage(app), app.tools::setDynamic)
        val id = try {
            val url = server.url("/mcp").newBuilder().host("localhost").build().toString()
            val (ok, message) = store.save(null, "Notes", url, "tok", emptyMap(), strings)
            assertTrue("save failed: $message", ok)

            // Registered under the server slug, gated even though the server says read-only.
            val registered = app.tools.tools.map { it.definition.name }.filter { it.startsWith("mcp_notes__") }
            assertEquals(listOf("mcp_notes__post_note", "mcp_notes__search"), registered.sorted())
            val search = app.tools.tools.first { it.definition.name == "mcp_notes__search" }
            assertEquals(ToolSensitivity.CHANGES_SOMETHING, search.sensitivity)
            assertEquals(ToolOrigin.MCP, search.origin)

            // The gate is asked (allowed here) and the server's text comes back.
            val gate = ToolGate { _, _ -> null }
            val executor = MasalaToolExecutor(app, app.tools, ActionLog(app), gate)
            val result = executor.execute("mcp_notes__search", buildJsonObject { put("q", "x") })
            assertTrue("unexpected: $result", result is ToolResult.Success)
            assertTrue((result as ToolResult.Success).output.contains("called search"))

            // A fresh store over the same prefs re-registers the tools (restart path).
            McpServerStore(PrefsMcpStorage(app), app.tools::setDynamic)
            val again = app.tools.tools.map { it.definition.name }.filter { it.startsWith("mcp_notes__") }
            assertEquals(2, again.size)

            store.servers.value.single().id
        } finally {
            store.servers.value.forEach { store.remove(it.id) }
        }
        assertNotNull(id)
        assertTrue(app.tools.tools.none { it.definition.name.startsWith("mcp_notes__") })
    }
}
