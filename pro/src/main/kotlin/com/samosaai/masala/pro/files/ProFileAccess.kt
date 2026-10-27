package com.samosaai.masala.pro.files

import android.content.Context
import android.os.Environment
import com.samosaai.masala.core.tool.FileAccess
import com.samosaai.masala.core.tool.FileAccessDeniedException
import com.samosaai.masala.core.tool.FileScope
import java.io.File

/**
 * `:pro`'s [FileAccess] (arch §3.1), from the path rules of the private prototype's `FileResolver`: any absolute or
 * relative path, checked on its canonical form. Relative paths resolve against [base]'s work folder.
 *
 * Permission model (API 30+, Masala's `minSdk`): the app's own storage needs nothing; anything below
 * shared storage needs `MANAGE_EXTERNAL_STORAGE` ("All files access"). Changed from the private prototype: paths
 * outside both are refused, where the private prototype let a read of, say, `/system` through, and the rules sit in a
 * [FileAccess] the whole tool set shares rather than in one resolver per file tool.
 *
 * [scope] follows the grant. Until the user grants All files access it reports
 * [FileScope.APP_STORAGE], which is what makes `ToolFiles` answer a path outside app storage with "needs
 * all-files access" (the capability to grant) instead of a plain refusal.
 */
class ProFileAccess(context: Context, private val base: FileAccess) : FileAccess {
    private val sandbox = listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null))
        .map { it.canonicalFile }

    /** Where relative paths start; [base] resolves "." to its work folder. */
    private val workDir: File get() = base.resolve(".")

    override val scope: FileScope
        get() = if (Environment.isExternalStorageManager()) FileScope.SHARED_STORAGE else FileScope.APP_STORAGE

    override fun resolve(path: String): File {
        val file = File(path).let { if (it.isAbsolute) it else File(workDir, path) }.canonicalFile
        if (sandbox.any { inside(file, it) }) return file
        if (!Environment.isExternalStorageManager()) {
            throw FileAccessDeniedException(path, "outside Masala's storage, which needs \"All files access\".")
        }
        if (!inside(file, Environment.getExternalStorageDirectory().canonicalFile)) {
            throw FileAccessDeniedException(path, "outside shared storage. Masala only reads and writes there and in its own folders.")
        }
        return file
    }

    override fun outputDir(): File = base.outputDir()

    private fun inside(file: File, root: File) = file.path == root.path || file.path.startsWith(root.path + File.separator)
}
