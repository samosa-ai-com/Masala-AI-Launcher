package com.samosaai.masala

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samosaai.masala.connectors.mcp.McpServerStore
import com.samosaai.masala.connectors.mcp.McpStrings
import com.samosaai.masala.connectors.mcp.McpToolNames
import com.samosaai.masala.connectors.mcp.PrefsMcpStorage
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.tools.ActionLog
import com.samosaai.masala.tools.MasalaToolExecutor
import com.samosaai.masala.tools.ToolGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real MCP server on the host machine, reached over the same plain http URL
 * the user types in Settings → Connected tools. Guards the transport rules that
 * a dev-only network config could hide: the emulator reaches the host at
 * 10.0.2.2 while a phone needs the machine's LAN IP, and a server that answers
 * `/mcp` with a 307 to `/mcp/` must still connect.
 *
 * Run on a phone without Gradle, so nothing is uninstalled and no grants are lost:
 *
 *   adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
 *   adb install --user 0 -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *   adb shell am instrument -w -e class com.samosaai.masala.McpLanTest \
 *     -e mcpUrl http://192.168.1.20:17600/mcp \
 *     -e mcpTool voicebox.list_profiles \
 *     com.samosaai.masala.test/androidx.test.runner.AndroidJUnitRunner
 *
 * `mcpTool`/`mcpArgs` are optional: without them the test only proves connect
 * and tool listing. Skips when the server isn't reachable. Servers the user
 * already saved are restored afterwards.
 */
@RunWith(AndroidJUnit4::class)
class McpLanTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private val storage = PrefsMcpStorage(app)
    private var previous: String? = null

    private fun arg(key: String): String? =
        InstrumentationRegistry.getArguments().getString(key)?.takeIf { it.isNotBlank() }

    private val url: String get() = arg("mcpUrl") ?: "http://10.0.2.2:8765/mcp/"

    private fun hostReachable(): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(parsed.host, parsed.port), 3000) }
            true
        }.getOrDefault(false)
    }

    @Before fun isolate() {
        previous = storage.load()
        storage.save(null)
        app.tools.setDynamic("mcp", emptyList())
    }

    @After fun restore() {
        runCatching {
            storage.save(previous)
            // Re-register whatever the user had, exactly as a restart would.
            McpServerStore(storage, app.tools::setDynamic)
        }
    }

    @Test fun connectListToolsAndOptionallyCall() = runBlocking {
        assumeTrue("no MCP server reachable at $url", hostReachable())
        val strings = McpStrings { id, args -> app.getString(id, *args) }
        val store = McpServerStore(storage, app.tools::setDynamic)
        var prefix = ""
        try {
            val (ok, message) = store.save(null, "lan-test", url, "", emptyMap(), strings)
            assertTrue("connect failed: $message", ok)
            val saved = store.servers.value.single()
            assertTrue("tools not listed", saved.tools.isNotEmpty())

            // The slug is derived from the server name, so read it rather than guess.
            val slug = McpToolNames.slugs(store.servers.value).getValue(saved.id)
            prefix = McpToolNames.PREFIX + slug + "__"
            val registered = app.tools.tools.map { it.definition.name }
            assertTrue("no tools registered: $registered", registered.any { it.startsWith(prefix) })

            val wanted = arg("mcpTool")
            if (wanted != null) {
                val exposed = McpToolNames.exposedNames(slug, saved.tools)
                    .entries.firstOrNull { it.value == wanted }?.key
                assertTrue("server has no tool '$wanted': ${saved.tools.map { it.name }}", exposed != null)
                val gate = ToolGate { _, _ -> null }
                val executor = MasalaToolExecutor(app, app.tools, ActionLog(app), gate)
                val args = arg("mcpArgs")?.let { Json.parseToJsonElement(it) as JsonObject } ?: JsonObject(emptyMap())
                val result = executor.execute(exposed!!, args)
                val success = requireNotNull(result as? ToolResult.Success) { "tool call failed: $result" }
                assertTrue("empty output", success.output.isNotBlank())
            }
        } finally {
            store.servers.value.forEach { store.remove(it.id) }
        }
        assertTrue(app.tools.tools.none { it.definition.name.startsWith(prefix) })
    }
}
