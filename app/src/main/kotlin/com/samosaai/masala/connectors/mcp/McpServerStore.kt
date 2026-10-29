package com.samosaai.masala.connectors.mcp

import androidx.annotation.StringRes
import com.samosaai.masala.R
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolOrigin
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** Where the server list is kept (app-private preferences in the app). */
interface McpStorage {
    fun load(): String?
    fun save(json: String?)
}

/** Looks up a message by string resource, so this class stays free of Context. */
fun interface McpStrings {
    operator fun invoke(@StringRes id: Int, vararg args: Any): String
}

/** The outcome of contacting a server: what it is and what it offers, or why it failed. */
sealed interface McpProbe {
    data class Ok(val server: McpServerInfo, val tools: List<McpTool>) : McpProbe
    data class Failed(val message: String) : McpProbe
}

/**
 * The user's own MCP servers (plan Phase 4 M5): added with a URL, an optional bearer token and
 * headers, each switched on or off, and their tools registered with the tool registry through
 * [register] as `mcp_<server>__<tool>`. Replaces the connector framework the client was written for.
 *
 * Every MCP tool is `CHANGES_SOMETHING` and uses the network, whatever the server's read-only hint
 * says: nothing else tells Masala what a remote tool does, so each call asks first, and unattended
 * runs never get one. Tokens sit in app-private storage, excluded from backups (plan §9 decision 8).
 */
class McpServerStore(
    private val storage: McpStorage,
    private val register: (owner: String, tools: List<Tool>) -> Unit,
    private val client: McpHttpClient = McpHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(McpServerConfig.serializer())
    private val lock = Any()
    private val sessions = mutableMapOf<String, McpHttpClient.Session>()

    private val _servers = MutableStateFlow(
        storage.load()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty(),
    )
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    /** Exposed tool name → (server id, the server's own tool name), for the servers that are on. */
    @Volatile private var routes: Map<String, Pair<String, String>> = emptyMap()

    init {
        registerTools()
    }

    fun server(id: String) = _servers.value.firstOrNull { it.id == id }

    /** Checks and contacts a server without saving anything: "Test connection". */
    suspend fun probe(url: String, token: String, headers: Map<String, String>, strings: McpStrings): McpProbe {
        validateMcpServer(url, token, headers)?.let { return McpProbe.Failed(problemMessage(it, strings)) }
        val draft = McpServerConfig(id = "probe", name = "probe", url = url.trim(), token = token, headers = headers)
        return try {
            val session = client.session(draft.endpoint())
            McpProbe.Ok(session.initialize(), session.listTools())
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            McpProbe.Failed(failureMessage(e, strings))
        }
    }

    /** Adds a server, or updates the one with [id], after a successful probe. Returns saved and a message. */
    suspend fun save(id: String?, name: String, url: String, token: String, headers: Map<String, String>, strings: McpStrings): Pair<Boolean, String> {
        if (name.isBlank()) return false to strings(R.string.mcp_error_name)
        val probe = probe(url, token, headers, strings)
        if (probe is McpProbe.Failed) return false to probe.message
        probe as McpProbe.Ok
        val tools = usableTools(probe.tools)
        val saved = synchronized(lock) {
            val existing = id?.let { server(it) }
            if (id != null && existing == null) return false to strings(R.string.mcp_removed_while_saving)
            val config = McpServerConfig(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                url = url.trim(),
                token = token.trim(),
                headers = headers,
                enabled = existing?.enabled ?: true,
                tools = tools,
                serverName = probe.server.name,
            )
            _servers.value = if (existing == null) _servers.value + config else _servers.value.map { if (it.id == config.id) config else it }
            sessions.remove(config.id)
            persistAndRegister()
            config
        }
        val label = saved.serverName.ifBlank { hostOf(saved.url) }
        val capped = if (probe.tools.size > MAX_TOOLS) " " + strings(R.string.mcp_tools_capped, MAX_TOOLS) else ""
        return true to strings(R.string.mcp_connected, label, saved.tools.size) + capped
    }

    fun remove(id: String) = synchronized(lock) {
        _servers.value = _servers.value.filterNot { it.id == id }
        sessions.remove(id)
        persistAndRegister()
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        _servers.value = _servers.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persistAndRegister()
    }

    /** Re-reads one server's tools. Returns a message for the screen. */
    suspend fun refresh(id: String, strings: McpStrings): String {
        val config = server(id) ?: return strings(R.string.mcp_removed_while_saving)
        return try {
            val tools = usableTools(sessionFor(config).listTools())
            commit(config) { it.copy(tools = tools, lastError = null) }
            strings(R.string.mcp_tools_count, tools.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            failureMessage(e, strings).also { message -> commit(config) { it.copy(lastError = message) } }
        }
    }

    /** Runs an exposed tool on its server. */
    suspend fun callTool(exposedName: String, args: JsonObject): ActionResult {
        val (serverId, toolName) = routes[exposedName] ?: return ActionResult.error("Tool '$exposedName' belongs to no MCP server that is switched on.")
        val config = server(serverId) ?: return ActionResult.error("The MCP server for '$exposedName' was removed.")
        return try {
            val output = sessionFor(config).callTool(toolName, args)
            if (output.isError) ActionResult.error(output.text) else ActionResult.ok(output.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            ActionResult.error("MCP server '${config.name}' failed to run '$toolName': ${e.message}")
        }
    }

    fun failureMessage(e: McpException, strings: McpStrings): String = when (e.kind) {
        McpFailure.AUTH -> strings(R.string.mcp_error_auth, e.httpCode)
        McpFailure.NOT_FOUND -> strings(R.string.mcp_error_not_found)
        McpFailure.OLD_TRANSPORT -> strings(R.string.mcp_error_old_transport)
        McpFailure.NOT_MCP -> strings(R.string.mcp_error_not_mcp, e.detail.take(DETAIL_CHARS))
        McpFailure.HTTP -> strings(R.string.mcp_error_http, e.httpCode, e.detail.take(DETAIL_CHARS))
        McpFailure.RPC -> strings(R.string.mcp_error_rpc, e.detail.take(DETAIL_CHARS))
        McpFailure.NETWORK -> strings(R.string.mcp_error_network, e.detail.take(DETAIL_CHARS))
    }

    private fun problemMessage(problem: McpConfigProblem, strings: McpStrings) = when (problem) {
        McpConfigProblem.BAD_URL -> strings(R.string.mcp_error_bad_url)
        McpConfigProblem.CLEARTEXT_WITH_SECRET -> strings(R.string.mcp_error_cleartext)
        McpConfigProblem.BAD_HEADER -> strings(R.string.mcp_error_bad_header)
        McpConfigProblem.BAD_TOKEN -> strings(R.string.mcp_error_bad_token)
    }

    private fun sessionFor(config: McpServerConfig): McpHttpClient.Session = synchronized(lock) {
        sessions.getOrPut(config.id) { client.session(config.endpoint()) }
    }

    /** Applies [change] unless the server was edited or removed since [seen] was read. */
    private fun commit(seen: McpServerConfig, change: (McpServerConfig) -> McpServerConfig) {
        synchronized(lock) {
            val current = server(seen.id) ?: return
            if (current.url != seen.url || current.token != seen.token || current.headers != seen.headers) return
            _servers.value = _servers.value.map { if (it.id == seen.id) change(it) else it }
            persistAndRegister()
        }
    }

    private fun persistAndRegister() {
        storage.save(if (_servers.value.isEmpty()) null else json.encodeToString(serializer, _servers.value))
        registerTools()
    }

    /** Registers the tools of every server that is on, replacing what was registered before. */
    private fun registerTools() {
        val list = _servers.value
        val slugs = McpToolNames.slugs(list)
        val tools = mutableListOf<Tool>()
        val newRoutes = mutableMapOf<String, Pair<String, String>>()
        list.filter { it.enabled }.forEach { server ->
            val byName = server.tools.associateBy { it.name }
            McpToolNames.exposedNames(slugs.getValue(server.id), server.tools).forEach { (exposed, original) ->
                val tool = byName.getValue(original)
                tools += McpServerTool(
                    ToolDefinition(exposed, "[MCP server \"${server.name}\"] ${tool.description}".take(MAX_DESCRIPTION), tool.inputSchema),
                    this,
                )
                newRoutes[exposed] = server.id to original
            }
        }
        routes = newRoutes
        register(OWNER, tools)
    }

    companion object {
        const val OWNER = "mcp"

        /** Tools kept per server: past this, a server costs more prompt than it is likely worth. */
        const val MAX_TOOLS = 64
        private const val MAX_DESCRIPTION = 1024
        private const val DETAIL_CHARS = 200

        /** The tools a model can be offered: object schemas only, capped at [MAX_TOOLS]. */
        internal fun usableTools(tools: List<McpTool>): List<McpTool> =
            tools.mapNotNull { tool -> normalizedInputSchema(tool.inputSchema)?.let { tool.copy(inputSchema = it) } }.take(MAX_TOOLS)

        fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url
    }
}

/** One MCP server tool as the registry sees it. Always asks first and always uses the network. */
private class McpServerTool(override val definition: ToolDefinition, private val store: McpServerStore) : Tool {
    override val sensitivity = ToolSensitivity.CHANGES_SOMETHING
    override val origin = ToolOrigin.MCP
    override val usesNetwork = true

    override suspend fun execute(args: JsonObject): ToolResult = store.callTool(definition.name, args).toToolResult()
}
