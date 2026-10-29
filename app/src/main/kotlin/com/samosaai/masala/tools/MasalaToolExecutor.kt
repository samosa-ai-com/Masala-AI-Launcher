package com.samosaai.masala.tools

import android.content.Context
import android.util.Log
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolRegistry
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import kotlinx.coroutines.CancellationException
import com.samosaai.masala.permissions.CapabilityPrompt
import com.samosaai.masala.permissions.PermissionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * Asks the user for a missing permission and waits for the answer, so a tool never reports
     * "not granted" while the dialog is still on screen. Null in tests and headless callers.
     */
    private val capabilities: CapabilityPrompt = PermissionGate,
) {
    private val askLock = Mutex()
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
            val marker = capability.marker
            // Wait for the answer instead of failing the call while the dialog is open.
            if (marker == null || !askAndVerify(marker) || !capability.isAvailable(context)) {
                return ToolResult.NeedsCapability(
                    marker ?: capability.name,
                    "This needs ${capability.label}, which Masala doesn't have yet. Ask the user to grant it and try again.",
                )
            }
        }
        if (tool.sensitivity != ToolSensitivity.READ_ONLY) {
            gate.check(tool, args)?.let { return it }
        }
        val result = try {
            withContext(Dispatchers.IO) { tool.execute(args) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.Failure("Tool '${tool.definition.name}' failed: ${e.message}")
        }
        // A tool can discover a missing permission only while running (Calendar, Contacts): ask,
        // then repeat the same call once, so the user's grant is never wasted.
        if (result is ToolResult.NeedsCapability && askAndVerify(result.capability)) {
            return try {
                withContext(Dispatchers.IO) { tool.execute(args) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.Failure("Tool '${tool.definition.name}' failed: ${e.message}")
            }
        }
        return result
    }

    /** Asks the UI for [marker] and reports whether the phone now holds it. One ask at a time. */
    private suspend fun askAndVerify(marker: String): Boolean {
        return askLock.withLock {
            val answered = try {
                capabilities.request(marker)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Permission request failed for $marker", e)
                false
            }
            answered && com.samosaai.masala.permissions.hasMarker(context, marker)
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
