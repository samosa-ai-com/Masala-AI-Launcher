package com.samosaai.masala.files

import android.content.Context
import com.samosaai.masala.core.tool.FileAccess
import com.samosaai.masala.core.tool.FileAccessDeniedException
import com.samosaai.masala.core.tool.FileScope
import java.io.File

/**
 * `:app`'s [FileAccess] (arch §3.1): app storage, where picked files are copied to. Relative paths
 * resolve under the work folder. `:pro` replaces it with all-files access.
 */
class AppStorageFileAccess(context: Context) : FileAccess {
    private val roots = listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null))
        .map { it.canonicalFile }
    private val work = File(context.filesDir, "work").apply { mkdirs() }
    private val output = (context.getExternalFilesDir("output") ?: File(context.filesDir, "output")).apply { mkdirs() }

    override val scope = FileScope.APP_STORAGE

    override fun resolve(path: String): File {
        val file = File(path).let { if (it.isAbsolute) it else File(work, path) }.canonicalFile
        if (roots.none { root -> file.path == root.path || file.path.startsWith(root.path + File.separator) }) {
            throw FileAccessDeniedException(
                path,
                "outside Masala's storage. Ask the user to pick the file, which puts a copy here.",
            )
        }
        return file
    }

    override fun outputDir(): File = output
}
