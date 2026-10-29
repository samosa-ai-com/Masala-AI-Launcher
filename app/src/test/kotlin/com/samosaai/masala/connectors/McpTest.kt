package com.samosaai.masala.connectors

import com.samosaai.masala.connectors.mcp.McpEndpoint
import com.samosaai.masala.connectors.mcp.McpException
import com.samosaai.masala.connectors.mcp.McpFailure
import com.samosaai.masala.connectors.mcp.McpHttpClient
import com.samosaai.masala.connectors.mcp.McpProbe
import com.samosaai.masala.connectors.mcp.McpConfigProblem
import com.samosaai.masala.connectors.mcp.McpServerStore
import com.samosaai.masala.connectors.mcp.McpStorage
import com.samosaai.masala.connectors.mcp.McpStrings
import com.samosaai.masala.connectors.mcp.McpToolNames
import com.samosaai.masala.connectors.mcp.isCleartextWithSecret
import com.samosaai.masala.connectors.mcp.normalizedInputSchema
import com.samosaai.masala.connectors.mcp.parseHeaderLines
import com.samosaai.masala.connectors.mcp.validateMcpServer
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolOrigin
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.core.tool.name
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()
    private var sse = false
    private var failWith: Int? = null
    private var redirectTo: String? = null
    private val missing = mutableSetOf<String>()
    private val methods = mutableListOf<String>()

    private val toolsJson = """[
        {"name":"search","description":"Search things","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},"annotations":{"readOnlyHint":true}},
        {"name":"post note","description":"Write","inputSchema":{"type":"object"}},
        {"name":"bad","description":"no object","inputSchema":{"type":"string"}}
    ]"""

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path!!.substringBefore('?')
                if (path in missing) return MockResponse().setResponseCode(404).setBody("nope")
                redirectTo?.let { dest -> if (path != dest) return MockResponse().setResponseCode(307).addHeader("Location", dest) }
                failWith?.let { return MockResponse().setResponseCode(it).setBody("nope") }
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val id = body["id"]?.jsonPrimitive?.content
                val method = body["method"]!!.jsonPrimitive.content
                methods += method
                if (id == null) return MockResponse().setResponseCode(202)
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-06-18","serverInfo":{"name":"Notes","version":"1"},"capabilities":{}}"""
                    "tools/list" -> """{"tools":$toolsJson}"""
                    "tools/call" -> """{"content":[{"type":"text","text":"called ${body["params"]!!.jsonObject["name"]!!.jsonPrimitive.content}"}]}"""
                    else -> "{}"
                }
                val rpc = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
                val response = MockResponse().addHeader("Mcp-Session-Id", "sess-1")
                return if (sse) response.addHeader("Content-Type", "text/event-stream").setBody("event: message\ndata: $rpc\n\n")
                else response.addHeader("Content-Type", "application/json").setBody(rpc)
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun url() = server.url("/mcp").toString()
    private val strings = McpStrings { id, args -> "s$id:" + args.joinToString(",") }

    private class Storage : McpStorage {
        var saved: String? = null
        override fun load() = saved
        override fun save(json: String?) { saved = json }
    }

    private class Registry {
        var tools: List<Tool> = emptyList()
        fun register(owner: String, list: List<Tool>) { assertEquals("mcp", owner); tools = list }
    }

    @Test fun clientInitializesListsAndCallsKeepingTheSession() = runBlocking {
        val session = McpHttpClient().session(McpEndpoint(url()))
        assertEquals("Notes", session.initialize().name)
        val tools = session.listTools()
        assertEquals(listOf("search", "post note", "bad"), tools.map { it.name })
        assertTrue(tools[0].readOnly)
        assertEquals("called search", session.callTool("search", JsonObject(emptyMap())).text)
        val later = seen.last()
        assertEquals("sess-1", later.getHeader("Mcp-Session-Id"))
        assertEquals("2025-06-18", later.getHeader("MCP-Protocol-Version"))
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), methods)
    }

    @Test fun clientReadsAnEventStreamReply() = runBlocking {
        sse = true
        val session = McpHttpClient().session(McpEndpoint(url()))
        session.initialize()
        assertEquals("called x", session.callTool("x", JsonObject(emptyMap())).text)
    }

    @Test fun clientReportsAuthAndMissingServers() = runBlocking<Unit> {
        failWith = 401
        assertEquals(McpFailure.AUTH, assertFailsWith<McpException> { McpHttpClient().session(McpEndpoint(url())).initialize() }.kind)
        failWith = 404
        assertEquals(McpFailure.NOT_FOUND, assertFailsWith<McpException> { McpHttpClient().session(McpEndpoint(url())).initialize() }.kind)
    }

    @Test fun clientSendsTheBearerToken() = runBlocking {
        val store = McpServerStore(Storage(), Registry()::register)
        store.save(null, "Notes", url(), "secret", emptyMap(), strings)
        assertTrue(seen.all { it.getHeader("Authorization") == "Bearer secret" })
    }

    @Test fun savingRegistersGatedNetworkToolsUnderTheServerSlug() = runBlocking {
        val registry = Registry()
        val store = McpServerStore(Storage(), registry::register)
        val (saved, _) = store.save(null, "My Notes", url(), "", emptyMap(), strings)
        assertTrue(saved)
        // The non-object schema is dropped; names are prefixed and cleaned.
        assertEquals(listOf("mcp_my_notes__search", "mcp_my_notes__post_note"), registry.tools.map { it.name })
        registry.tools.forEach {
            assertEquals(ToolSensitivity.CHANGES_SOMETHING, it.sensitivity, "the server's read-only hint must not skip the gate")
            assertEquals(ToolOrigin.MCP, it.origin)
            assertTrue(it.usesNetwork)
        }
    }

    @Test fun registeredToolCallsTheServer() = runBlocking {
        val registry = Registry()
        McpServerStore(Storage(), registry::register).save(null, "Notes", url(), "", emptyMap(), strings)
        val result = registry.tools.first { it.name == "mcp_notes__post_note" }.execute(JsonObject(emptyMap()))
        assertEquals(ToolResult.Success("called post note"), result)
    }

    @Test fun disablingAndRemovingUnregisterTheTools() = runBlocking {
        val registry = Registry()
        val store = McpServerStore(Storage(), registry::register)
        store.save(null, "Notes", url(), "", emptyMap(), strings)
        val id = store.servers.value.single().id
        store.setEnabled(id, false)
        assertTrue(registry.tools.isEmpty())
        store.setEnabled(id, true)
        assertEquals(2, registry.tools.size)
        store.remove(id)
        assertTrue(registry.tools.isEmpty())
        assertTrue(store.servers.value.isEmpty())
    }

    @Test fun serversAndToolsSurviveARestart() = runBlocking {
        val storage = Storage()
        McpServerStore(storage, Registry()::register).save(null, "Notes", url(), "tok", emptyMap(), strings)
        val registry = Registry()
        val restarted = McpServerStore(storage, registry::register)
        assertEquals("Notes", restarted.servers.value.single().name)
        assertEquals(2, registry.tools.size)
    }

    @Test fun failedProbeSavesNothing() = runBlocking {
        failWith = 401
        val storage = Storage()
        val registry = Registry()
        val (saved, message) = McpServerStore(storage, registry::register).save(null, "Notes", url(), "", emptyMap(), strings)
        assertEquals(false, saved)
        assertTrue(message.isNotEmpty())
        assertNull(storage.saved)
        assertTrue(registry.tools.isEmpty())
    }

    @Test fun probeOkReportsServerAndTools() = runBlocking {
        val p = McpServerStore(Storage(), Registry()::register).probe(url(), "", emptyMap(), strings)
        assertIs<McpProbe.Ok>(p)
        assertEquals("Notes", p.server.name)
    }

    @Test fun cleartextWithASecretWarnsInsteadOfRefusing() {
        // Accepted everywhere now; the screen warns when a secret leaves the local network over http.
        assertNull(validateMcpServer("http://example.com/mcp", "tok", emptyMap()))
        assertTrue(isCleartextWithSecret("http://example.com/mcp", "tok", emptyMap()))
        assertTrue(isCleartextWithSecret("http://example.com/mcp", "", mapOf("X-Key" to "v")))
        assertTrue(!isCleartextWithSecret("http://192.168.1.5/mcp", "tok", emptyMap()))
        assertTrue(!isCleartextWithSecret("http://10.0.2.2:8080/mcp", "tok", emptyMap()))
        assertTrue(!isCleartextWithSecret("http://example.com/mcp", "", emptyMap()))
        assertTrue(!isCleartextWithSecret("https://example.com/mcp", "tok", emptyMap()))
        assertEquals(McpConfigProblem.BAD_URL, validateMcpServer("not a url", "", emptyMap()))
        assertEquals(McpConfigProblem.BAD_HEADER, validateMcpServer("https://x.y", "", mapOf("bad name" to "v")))
    }

    @Test fun sameOriginRedirectIsFollowedAndSticks() = runBlocking {
        redirectTo = "/mcp-final"
        val session = McpHttpClient().session(McpEndpoint(url()))
        assertEquals("Notes", session.initialize().name)
        assertTrue(session.lastRequestUrl.endsWith("/mcp-final"), session.lastRequestUrl)
        session.listTools()
        assertTrue(seen.last().path!!.startsWith("/mcp-final"), seen.last().path!!)
    }

    @Test fun crossHostRedirectNamesTheDestination() = runBlocking {
        redirectTo = "http://example.com/other"
        val e = assertFailsWith<McpException> { McpHttpClient().session(McpEndpoint(url())).initialize() }
        assertEquals(McpFailure.REDIRECT, e.kind)
        assertTrue(e.detail.contains("http://example.com/other"), e.detail)
    }

    @Test fun redirectLoopsFailInsteadOfHanging() = runBlocking {
        // The query survives but the path never lands: every attempt redirects again.
        redirectTo = "/mcp?x=1"
        val e = assertFailsWith<McpException> { McpHttpClient().session(McpEndpoint(url())).initialize() }
        assertEquals(McpFailure.HTTP, e.kind)
        assertEquals(6, seen.size)
    }

    @Test fun redirectTargetPolicy() {
        val client = McpHttpClient()
        assertEquals("http://h:1/b", client.redirectTarget("http://h:1/a", "/b", 307))
        assertEquals("https://h:1/a", client.redirectTarget("http://h:1/a", "https://h:1/a", 307))
        assertFailsWith<McpException> { client.redirectTarget("http://h:1/a", "http://other:1/a", 307) }
        assertFailsWith<McpException> { client.redirectTarget("http://h:1/a", "http://h:2/a", 307) }
        assertFailsWith<McpException> { client.redirectTarget("https://h/a", "http://h/a", 307) }
    }

    @Test fun slashSpellingIsRetriedOnceOn404() = runBlocking {
        missing += "/mcp"
        val session = McpHttpClient().session(McpEndpoint(url()))
        assertEquals("Notes", session.initialize().name)
        assertTrue(session.endpoint.url.endsWith("/mcp/"), session.endpoint.url)
        assertTrue(seen.any { it.path == "/mcp" } && seen.any { it.path == "/mcp/" })
    }

    @Test fun failedSlashRetryRestoresTheConfiguredUrl() = runBlocking {
        missing += "/mcp"
        missing += "/mcp/"
        val session = McpHttpClient().session(McpEndpoint(url()))
        val e = assertFailsWith<McpException> { session.initialize() }
        assertEquals(McpFailure.NOT_FOUND, e.kind)
        assertEquals(url(), session.endpoint.url)
    }

    @Test fun slashToggledTogglesOnlyTheTrailingSlash() {
        val client = McpHttpClient()
        assertEquals("http://h/mcp/", client.slashToggled("http://h/mcp"))
        assertEquals("http://h/mcp", client.slashToggled("http://h/mcp/"))
        assertNull(client.slashToggled("http://h/"))
        assertNull(client.slashToggled("not a url"))
    }

    @Test fun redirectFailureHasAMessage() {
        val store = McpServerStore(Storage(), Registry()::register)
        val message = store.failureMessage(McpException(McpFailure.REDIRECT, "http://x/y", 302), strings)
        assertTrue(message.contains("http://x/y"), message)
    }

    @Test fun headerLinesParse() {
        assertEquals(mapOf("X-Key" to "abc", "A" to "b"), parseHeaderLines("X-Key: abc\n\nA: b\n"))
        assertNull(parseHeaderLines("no colon here"))
    }

    @Test fun toolNamesAreCleanedShortenedAndUnique() {
        val a = McpToolNames.exposedNames("srv", listOf(tool("do it"), tool("do_it"))).keys.toList()
        assertEquals(2, a.toSet().size)
        assertTrue(a.all { it.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")) })
        val long = McpToolNames.exposedNames("srv", listOf(tool("x".repeat(100)))).keys.single()
        assertTrue(long.length <= 64)
    }

    @Test fun schemasAreMadeAcceptableToChatApis() {
        assertNull(normalizedInputSchema(Json.parseToJsonElement("""{"type":"string"}""").jsonObject))
        assertTrue("properties" in normalizedInputSchema(Json.parseToJsonElement("""{"type":"object"}""").jsonObject)!!)
        assertEquals("object", normalizedInputSchema(Json.parseToJsonElement("{}").jsonObject)!!["type"]!!.jsonPrimitive.content)
    }

    private fun tool(name: String) = com.samosaai.masala.connectors.mcp.McpTool(name, "", JsonObject(emptyMap()))
}
