package com.samosaai.masala.tools

import android.content.Context
import com.samosaai.masala.core.tool.ToolResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only local audit log of every tool call and every confirmation answer: timestamp, tool,
 * arguments, outcome. Stored in the app sandbox only. From the private prototype's ActionLog, recording `:core`'s
 * [ToolResult].
 */
class ActionLog(context: Context) {

    private val file: File = File(context.filesDir, "action_log.txt")
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun record(tool: String, args: String, result: ToolResult) {
        val (status, message) = when (result) {
            is ToolResult.Success -> "OK" to result.output
            is ToolResult.Failure -> "FAIL" to result.message
            is ToolResult.NeedsCapability -> "NEEDS ${result.capability}" to result.reason
        }
        val line = "${formatter.format(Date())}\t$tool\t$args\t$status\t${message.take(500).replace('\n', ' ')}\n"
        try {
            file.appendText(line)
            trimIfNeeded()
        } catch (_: Exception) {
            // Logging must never break tool execution.
        }
    }

    /** The newest [limit] lines, newest last. */
    fun tail(limit: Int = 50): List<String> = runCatching { file.readLines().takeLast(limit) }.getOrDefault(emptyList())

    private fun trimIfNeeded(maxBytes: Long = 512 * 1024) {
        if (file.length() > maxBytes) {
            val lines = file.readLines()
            file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
    }
}
