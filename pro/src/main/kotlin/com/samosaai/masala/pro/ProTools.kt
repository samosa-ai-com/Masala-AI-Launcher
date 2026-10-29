package com.samosaai.masala.pro

import android.content.Context
import android.os.Environment
import com.samosaai.masala.core.files.FileIndex
import com.samosaai.masala.core.files.TextExtractor
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.FunctionTool
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.core.tool.ToolOrigin
import com.samosaai.masala.core.tool.ToolRegistry
import com.samosaai.masala.core.tool.ToolSensitivity.CHANGES_SOMETHING
import com.samosaai.masala.core.tool.ToolSensitivity.READ_ONLY
import com.samosaai.masala.pro.data.ProDatabase
import com.samosaai.masala.pro.files.FileSearchTool
import com.samosaai.masala.pro.files.FileTool
import com.samosaai.masala.pro.files.GlobTool
import com.samosaai.masala.pro.files.GrepTool
import com.samosaai.masala.pro.files.ProFileAccess
import com.samosaai.masala.pro.files.ProFileIndex
import com.samosaai.masala.pro.media.MediaConvertTool
import com.samosaai.masala.pro.termux.TermuxBridge
import com.samosaai.masala.pro.ProToolDefinitions as Defs

/** The only entry point `:app` may use in `:pro` (plan 0.3). */
object ProTools {
    /**
     * Swaps in all-files access and adds the Play-restricted tools to [registry]: the file tools
     * (`list_files`, `read_file`, `write_file`, `glob`, `grep`), `file_search` over the file index, and
     * `media_convert` through Termux's ffmpeg. Every tool is registered with [ToolOrigin.PRO], so hub skills can't name them (arch §2.4).
     * The file tools read `registry.fileAccess` on every call, so the grant of All files access takes
     * effect without a restart. Returns the file index (arch §3.10), which reads documents through
     * [extractor].
     */
    fun register(registry: ToolRegistry, context: Context, extractor: TextExtractor): FileIndex {
        val app = context.applicationContext
        val index = ProFileIndex.get(app).also { it.extractor = extractor }
        index.refresh()
        registry.fileAccess = ProFileAccess(app, registry.fileAccess)
        val files = ToolFiles { registry.fileAccess }

        val fileTool = FileTool(files)
        val globTool = GlobTool(files)
        val grepTool = GrepTool(files)
        val convert = MediaConvertTool(files, TermuxBridge(app))
        val db = ProDatabase.get(app)
        val search = FileSearchTool(
            hits = { match, folder, limit -> db.fileIndex().hits(match, folder, limit) },
            // Seeing access here for the first time (the user just granted it) starts the first run.
            indexedFiles = { index.refresh(); index.status.files },
            hasAccess = { Environment.isExternalStorageManager() },
        )

        listOf(
            FunctionTool(Defs.listFiles, READ_ONLY, readsPrivateData = true, origin = ToolOrigin.PRO) {
                fileTool.listFiles(
                    path = it.string("path") ?: return@FunctionTool missing("path"),
                    recursive = it.boolean("recursive") ?: false,
                    sortBy = it.string("sort_by"),
                    include = it.string("include"),
                    exclude = it.string("exclude"),
                    maxDepth = it.int("max_depth"),
                )
            },
            FunctionTool(Defs.readFile, READ_ONLY, readsPrivateData = true, origin = ToolOrigin.PRO) {
                fileTool.readFile(
                    path = it.string("path") ?: return@FunctionTool missing("path"),
                    offset = it.int("offset"),
                    limit = it.int("limit"),
                    encoding = it.string("encoding"),
                )
            },
            FunctionTool(Defs.writeFile, CHANGES_SOMETHING, origin = ToolOrigin.PRO) {
                fileTool.writeFile(
                    path = it.string("path") ?: return@FunctionTool missing("path"),
                    content = it.string("content") ?: return@FunctionTool missing("content"),
                    append = it.boolean("append") ?: false,
                    binary = it.boolean("binary") ?: false,
                )
            },
            FunctionTool(Defs.glob, READ_ONLY, readsPrivateData = true, origin = ToolOrigin.PRO) {
                globTool.glob(
                    path = it.string("path") ?: return@FunctionTool missing("path"),
                    pattern = it.string("pattern") ?: return@FunctionTool missing("pattern"),
                )
            },
            FunctionTool(Defs.fileSearch, READ_ONLY, readsPrivateData = true, origin = ToolOrigin.PRO) {
                search.search(
                    query = it.string("query") ?: return@FunctionTool missing("query"),
                    folder = it.string("folder"),
                    limit = it.int("limit"),
                )
            },
            FunctionTool(Defs.grep, READ_ONLY, readsPrivateData = true, origin = ToolOrigin.PRO) {
                grepTool.grep(
                    path = it.string("path") ?: return@FunctionTool missing("path"),
                    pattern = it.string("pattern") ?: return@FunctionTool missing("pattern"),
                    include = it.string("include"),
                )
            },
            FunctionTool(Defs.mediaConvert, CHANGES_SOMETHING, readsPrivateData = true, origin = ToolOrigin.PRO) {
                convert.convert(
                    input = it.string("input") ?: return@FunctionTool missing("input"),
                    output = it.string("output") ?: return@FunctionTool missing("output"),
                    bitrate = it.string("bitrate"),
                    overwrite = it.boolean("overwrite") ?: false,
                )
            },
        ).forEach(registry::register)
        return index
    }

    private fun missing(param: String) = ActionResult.error("Missing or invalid required parameter '$param'.")
}
