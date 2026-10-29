package com.samosaai.masala.agent

import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import java.io.File

/** What a [MasalaAgent] run reports as it goes. Entry points (reply card, skill sheet) render these. */
sealed interface AgentEvent {
    data object Thinking : AgentEvent

    /** Text the model wrote alongside tool calls, before the final answer. */
    data class Text(val text: String) : AgentEvent
    data class ToolCall(val name: String, val args: JsonObject) : AgentEvent
    data class ToolDone(val name: String, val result: ToolResult) : AgentEvent

    /** A tool needs a permission or special access; the run stops so the UI can ask. */
    data class NeedsCapability(val capability: String, val reason: String) : AgentEvent

    /** What a tool made for the user to open: its files (kind from the extension) and its typed results. */
    data class Results(val items: List<Produced>) : AgentEvent
    data class Done(val text: String) : AgentEvent
    data class Failed(val message: String) : AgentEvent
    data object StepLimit : AgentEvent
}

/** A successful call's files and typed results as one list, files first. */
internal fun ToolResult.Success.results(): List<Produced> =
    files.map { Produced(fileKind(it), File(it).name, it) } + produced

private fun fileKind(path: String) = when (File(path).extension.lowercase()) {
    "mp3", "m4a", "wav", "ogg", "flac", "aac" -> "audio"
    "mp4", "mkv", "webm", "mov", "3gp" -> "video"
    else -> "file"
}
