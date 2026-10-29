package com.samosaai.masala.skills

import android.util.Log
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.llm.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Skill run conversations, one JSON file per run in [dir], so a long run never outgrows a database row. */
class TranscriptFiles(private val dir: File) {
    private val serializer = ListSerializer(ChatMessage.serializer())

    /** The saved conversation, or null when the run has no file (runs saved before files were used). */
    suspend fun read(runId: Long): List<ChatMessage>? = withContext(Dispatchers.IO) {
        val file = fileFor(runId).takeIf { it.isFile } ?: return@withContext null
        runCatching { MasalaJson.lenient.decodeFromString(serializer, file.readText()) }
            .onFailure { Log.w(TAG, "Unreadable transcript for run $runId", it) }
            .getOrNull()
    }

    /** Replaces the run's file; written to a temporary file first, so a crash mid-write keeps the old one. */
    suspend fun write(runId: Long, history: List<ChatMessage>) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$runId.json.tmp")
            tmp.writeText(MasalaJson.strict.encodeToString(serializer, history))
            if (!tmp.renameTo(fileFor(runId))) error("rename failed")
        }.onFailure { Log.w(TAG, "Couldn't save the transcript of run $runId", it) }
        Unit
    }

    private fun fileFor(runId: Long) = File(dir, "$runId.json")

    private companion object {
        const val TAG = "TranscriptFiles"
    }
}
