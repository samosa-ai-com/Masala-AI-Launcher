package com.samosaai.masala.core.tool

import java.io.File

/**
 * How file, media and document tools reach files (arch §3.1). `:app`'s implementation works in app
 * storage and on copies of picked files; `:pro`'s wraps the private prototype's `FileResolver` with all-files
 * access. Dropping `:pro` leaves every tool working on picked files: the Play-safe rework (arch §3.10).
 */
interface FileAccess {
    val scope: FileScope

    /**
     * Turns a path from the user, the model or a picker into a file the tool may use, or throws
     * [FileAccessDeniedException] with a reason the model can pass on.
     */
    fun resolve(path: String): File

    /** Where results go when the user didn't say. */
    fun outputDir(): File
}

enum class FileScope {
    /** App storage and picker copies only. */
    APP_STORAGE,

    /** Anywhere in shared storage (`MANAGE_EXTERNAL_STORAGE`, `:pro` only). */
    SHARED_STORAGE,
}

class FileAccessDeniedException(val path: String, reason: String) : Exception("$path: $reason")
