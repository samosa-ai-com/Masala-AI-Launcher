package com.samosaai.masala.core.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Decides whether a tool call goes through the confirmation gate (arch §3.6). */
enum class ToolSensitivity {
    /** Runs without asking. Scheduled runs nobody is watching get only these. */
    READ_ONLY,

    /** Asks first: alarms, modes, layout edits, reminders. */
    CHANGES_SOMETHING,

    /** Asks first and says what is lost: deleting, uninstalling, overwriting. */
    DESTRUCTIVE,
}

/** Which module registered a tool. Hub skills never get [PRO] tools (arch §2.4). */
enum class ToolOrigin { CORE, PRO, MCP }

/** The OpenAI-style function definition sent to the model. */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * Something a tool made that the user can open from a run: [kind] is a `SkillOutput` name (`file`,
 * `audio`, `video`, `mini_app`, `installed_app`); [ref] is a path, a mini-app id or a package name.
 */
data class Produced(val kind: String, val title: String, val ref: String)

sealed interface ToolResult {
    /** [files] are paths the tool wrote; [produced] are results with a known kind (a mini app, say). */
    data class Success(
        val output: String,
        val files: List<String> = emptyList(),
        val produced: List<Produced> = emptyList(),
    ) : ToolResult
    data class Failure(val message: String) : ToolResult

    /** The tool needs a capability (permission, special access) the user hasn't granted yet. */
    data class NeedsCapability(val capability: String, val reason: String) : ToolResult
}

/**
 * A tool the agent can call. Implemented in `:app` (Play-safe) and `:pro` (Play-restricted); both
 * register into one [ToolRegistry].
 */
interface Tool {
    val definition: ToolDefinition
    val sensitivity: ToolSensitivity
    val origin: ToolOrigin get() = ToolOrigin.CORE

    /** Reads the user's private data: files, wiki, notifications, calendar, contacts. */
    val readsPrivateData: Boolean get() = false

    /** Sends anything off the phone: web fetch or search, MCP, uploads. */
    val usesNetwork: Boolean get() = false

    /**
     * Brings up a screen (another app, a dialer, a sheet). Needs someone at the phone, so runs nobody
     * is watching never get it: Android blocks the screen from the background.
     */
    val opensScreen: Boolean get() = false

    suspend fun execute(args: JsonObject): ToolResult
}

val Tool.name: String get() = definition.name

/** The one registry every tool goes through, including `:pro`'s (arch §3.6). */
interface ToolRegistry {
    fun register(tool: Tool)
    operator fun get(name: String): Tool?
    val tools: List<Tool>

    /**
     * Where file tools may read and write. `:app` starts with app storage only; `:pro` replaces it
     * with all-files access. Tools read this at call time, not at construction.
     */
    var fileAccess: FileAccess
}

/** A plain in-memory [ToolRegistry]. */
class DefaultToolRegistry(override var fileAccess: FileAccess) : ToolRegistry {
    private val byName = LinkedHashMap<String, Tool>()

    override fun register(tool: Tool) {
        require(tool.name !in byName) { "Tool ${tool.name} is already registered" }
        byName[tool.name] = tool
    }

    override fun get(name: String): Tool? = byName[name]

    override val tools: List<Tool> get() = byName.values.toList()
}
