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
    /** This user's shared storage (`/storage/emulated/<user>`), where folder names point. */
    private val storage: String,
    private val indexing: () -> Boolean = { false },
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
            if (indexing()) {
                return ActionResult.error(
                    "The file index is being built for the first time and has nothing yet. Use glob to find " +
                        "files by name or grep to search text files, or try file_search again in a few minutes."
                )
            }
            return ActionResult.error(
                "The file index is empty: it hasn't run yet. Use glob to find files by name or grep to " +
                    "search text files, or ask the user to run Index now in Settings → Files & search."
            )
        }
        val found = hits(match, likePrefix(folderPrefix(folder, storage)), (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT))
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
        /** A path prefix for the folder the model named: absolute, `/sdcard/…`, or relative to [storage]. */
        internal fun folderPrefix(folder: String?, storage: String): String {
            val f = folder?.trim()?.trimEnd('/').orEmpty()
            return when {
                f.isEmpty() -> "/"
                f == "/sdcard" || f.startsWith("/sdcard/") -> storage + f.removePrefix("/sdcard") + "/"
                f.startsWith("/") -> "$f/"
                else -> "$storage/$f/"
            }
        }

        /** [prefix] for `LIKE … ESCAPE '\'`, so `%` and `_` in a folder name match only themselves. */
        internal fun likePrefix(prefix: String): String =
            prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
