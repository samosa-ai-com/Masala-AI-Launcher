package com.samosaai.masala.pro.files

import com.samosaai.masala.core.files.SearchQuery
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.pro.data.FileHit
import java.time.Instant
import java.time.ZoneId

/**
 * `file_search` (plan Phase 3 Lane B): finds files in shared storage by name and text through the
 * file index, with the passage that matched. Never reads files itself; `glob` and `grep` cover what
 * isn't indexed yet.
 */
class FileSearchTool(
    private val hits: suspend (match: String, folder: String, limit: Int) -> List<FileHit>,
    private val indexedFiles: () -> Int,
    private val hasAccess: () -> Boolean,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun search(query: String, folder: String?, limit: Int?): ActionResult {
        if (!hasAccess()) {
            return ActionResult.permissionNeeded(
                ToolFiles.ALL_FILES_ACCESS,
                "file_search needs All files access, so Masala can index files outside its own folder.",
            )
        }
        val match = SearchQuery.match(query) ?: return ActionResult.error("Give some words to search for.")
        if (indexedFiles() == 0) {
            return ActionResult.error(
                "The file index is empty: it hasn't run yet. Use glob to find files by name or grep to " +
                    "search text files, or ask the user to run Index now in Settings → Files & search."
            )
        }
        val found = hits(match, folderPrefix(folder), (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT))
        if (found.isEmpty()) {
            return ActionResult.ok("No indexed file matches \"$query\". Files changed since the last index run, and text past the first part of long documents, aren't covered: grep can search text files directly.")
        }
        return ActionResult.ok(format(found))
    }

    internal fun format(found: List<FileHit>): String = found.mapIndexed { i, hit ->
        val date = Instant.ofEpochMilli(hit.modified).atZone(zone).toLocalDate()
        val passage = hit.passage.takeIf { it.isNotBlank() && it != hit.name }?.let { "\n   $it" }.orEmpty()
        "${i + 1}. ${hit.path} (modified $date)$passage"
    }.joinToString("\n")

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
        private const val STORAGE = "/storage/emulated/0"

        /** A path prefix for the folder the model named: absolute, `/sdcard/…`, or relative to storage. */
        internal fun folderPrefix(folder: String?): String {
            val f = folder?.trim()?.trimEnd('/').orEmpty()
            return when {
                f.isEmpty() -> "/"
                f == "/sdcard" || f.startsWith("/sdcard/") -> STORAGE + f.removePrefix("/sdcard") + "/"
                f.startsWith("/") -> "$f/"
                else -> "$STORAGE/$f/"
            }
        }
    }
}
