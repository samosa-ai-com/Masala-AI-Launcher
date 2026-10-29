package com.samosaai.masala.debug

import com.samosaai.masala.core.tool.ToolResult

/** One row of the debug screen's log. */
sealed interface DebugEvent {
    data class Raw(val json: String) : DebugEvent
    data class Call(val name: String, val args: String) : DebugEvent
    data class Result(val name: String, val result: ToolResult) : DebugEvent
    data class Answer(val text: String) : DebugEvent
    data class Error(val message: String) : DebugEvent
}
