package com.samosaai.masala.skills

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Report" on AI output (store policy wants an in-app way to flag it). Reports stay on the phone in a
 * text file; the debug screen shows them. Nothing is sent anywhere.
 */
class AiReports(context: Context) {
    private val file = File(context.filesDir, "ai_reports.txt")
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun add(skillId: String, reasons: List<String>, note: String, text: String) {
        val line = listOf(formatter.format(Date()), skillId, reasons.joinToString(","), note, text.take(500))
            .joinToString("\t") { it.replace('\n', ' ').replace('\t', ' ') }
        runCatching { file.appendText(line + "\n") }
    }

    fun tail(limit: Int = 50): List<String> = runCatching { file.readLines().takeLast(limit) }.getOrDefault(emptyList())
}
