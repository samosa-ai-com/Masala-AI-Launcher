package com.samosaai.masala.agent

import com.samosaai.masala.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/** What a [MasalaAgent] run reports as it goes. Entry points (reply card, skill sheet) render these. */
sealed interface AgentEvent {
    data object Thinking : AgentEvent

    /** Text the model wrote alongside tool calls, before the final answer. */
    data class Text(val text: String) : AgentEvent
    data class ToolCall(val name: String, val args: JsonObject) : AgentEvent
    data class ToolDone(val name: String, val result: ToolResult) : AgentEvent

    /** A tool needs a permission or special access; the run stops so the UI can ask. */
    data class NeedsCapability(val capability: String, val reason: String) : AgentEvent

    /** Files a tool produced. */
    data class Files(val paths: List<String>) : AgentEvent
    data class Done(val text: String) : AgentEvent
    data class Failed(val message: String) : AgentEvent
    data object StepLimit : AgentEvent
}
