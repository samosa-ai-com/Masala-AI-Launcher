package com.samosaai.masala.core.tool

import java.io.File

/**
 * How the media and document tools copied from the private prototype reach files: the calls they made on the private prototype's
 * `FileResolver`, answered by `:core`'s [FileAccess] (arch §3.1). [access] is read on every call, so
 * the all-files access `:pro` swaps in applies to tools created before it.
 */
class ToolFiles(private val access: () -> FileAccess) {

    sealed class ResolveResult {
        data class Ok(val file: File) : ResolveResult()
        data class PermissionNeeded(val result: ActionResult) : ResolveResult()
        data class Error(val message: String) : ResolveResult()
    }

    /** Where relative paths and default outputs go. */
    val workDir: File get() = access().outputDir()

    fun resolveForRead(path: String): ResolveResult = resolve(path)

    fun resolveForWrite(path: String): ResolveResult = resolve(path)

    fun checkReadPermission(file: File): ActionResult? = (resolve(file.path) as? ResolveResult.PermissionNeeded)?.result

    fun checkWritePermission(file: File): ActionResult? = checkReadPermission(file)

    fun formatSize(bytes: Long): String = formatSizeStatic(bytes)

    private fun resolve(path: String): ResolveResult = try {
        ResolveResult.Ok(access().resolve(path))
    } catch (e: FileAccessDeniedException) {
        // App storage only: all-files access (:pro) would allow it, so say what to grant.
        if (access().scope == FileScope.APP_STORAGE) {
            ResolveResult.PermissionNeeded(ActionResult.permissionNeeded(ALL_FILES_ACCESS, e.message ?: path))
        } else {
            ResolveResult.Error(e.message ?: "Can't use $path")
        }
    }

    companion object {
        /** The marker for "All files access", which `:pro` asks for. */
        const val ALL_FILES_ACCESS = "special:all_files_access"

        /** Starts a tool result that carries an image for the vision model (the private prototype's `FileResolver.IMAGE_DATA_PREFIX`). */
        const val IMAGE_DATA_PREFIX = "IMAGE_DATA:"

        fun formatSizeStatic(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
            bytes >= 1024L -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
