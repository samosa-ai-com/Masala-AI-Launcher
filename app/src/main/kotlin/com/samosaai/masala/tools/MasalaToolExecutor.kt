package com.samosaai.masala.tools

import android.content.Context
import android.util.Log
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolRegistry
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Asked before every tool that isn't read-only runs (arch §3.6). Phase 2's ConfirmationGate (a Compose
 * sheet) implements it; null lets the tool run, anything else is returned in its place.
 */
fun interface ToolGate {
    suspend fun check(tool: Tool, args: JsonObject): ToolResult?
}

/**
 * The single place tools run, from the private prototype's ToolExecutor: unknown tools are refused, a missing special
 * access is reported instead of running, the gate is asked for anything that changes something, and
 * every call goes into the [ActionLog].
 */
class MasalaToolExecutor(
    private val context: Context,
    private val registry: ToolRegistry,
    private val actionLog: ActionLog,
    private val gate: ToolGate,
) {
    suspend fun execute(name: String, args: JsonObject): ToolResult {
        val tool = registry[name] ?: return ToolResult.Failure("Unknown tool '$name'. Only the listed tools are available.")
        val result = run(tool, args)
        // Tool payloads can hold private data, so only the kind of result goes to logcat.
        Log.d(TAG, "$name -> ${result::class.simpleName}")
        actionLog.record(name, args.redactedForAudit(), result)
        return result
    }

    private suspend fun run(tool: Tool, args: JsonObject): ToolResult {
        Capability.of(tool.definition.name)?.takeUnless { it.isAvailable(context) }?.let { capability ->
            return ToolResult.NeedsCapability(
                capability.marker ?: capability.name,
                "This needs ${capability.label}, which Masala doesn't have yet. Ask the user to grant it and try again.",
            )
        }
        if (tool.sensitivity != ToolSensitivity.READ_ONLY) {
            gate.check(tool, args)?.let { return it }
        }
        return try {
            withContext(Dispatchers.IO) { tool.execute(args) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Failure("Tool '${tool.definition.name}' failed: ${e.message}")
        }
    }

    /** Arguments as they may be written to the log: secrets by their nature are replaced. */
    private fun JsonObject.redactedForAudit(): String =
        if (keys.none { it in REDACTED_KEYS }) {
            toString()
        } else {
            JsonObject(mapValues { (key, value) -> if (key in REDACTED_KEYS) JsonPrimitive("(redacted)") else value }).toString()
        }

    private companion object {
        const val TAG = "MasalaToolExecutor"
        val REDACTED_KEYS = setOf("stdin", "password")
    }
}
