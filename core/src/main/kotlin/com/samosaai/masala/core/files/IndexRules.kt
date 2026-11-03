package com.samosaai.masala.core.files

/**
 * What the file index walks and reads (arch §3.10): everything in shared storage except `Android/`,
 * hidden folders and the standard media folders. Every file's name is indexed; text only from
 * documents and text files under [MAX_CONTENT_BYTES], keeping the first [MAX_TEXT_CHARS].
 */
object IndexRules {
    const val MAX_FILES = 20_000

    /** The document parser refuses larger files anyway. */
    const val MAX_CONTENT_BYTES = 20L * 1024 * 1024
    const val MAX_TEXT_CHARS = 32 * 1024

    /** Top-level folders left out: app data, and media that holds no text worth searching. */
    private val SKIPPED_TOP = setOf(
        "android", "dcim", "pictures", "movies", "music", "podcasts", "ringtones", "alarms",
        "notifications", "recordings", "audiobooks",
    )

    /** What the document parser reads. */
    private val CONTENT_EXTENSIONS = setOf(
        "pdf", "docx", "xlsx", "pptx", "html", "htm", "rtf",
        "txt", "text", "csv", "tsv", "md", "markdown", "json", "xml", "log", "yml", "yaml", "ini", "cfg",
        "conf", "properties", "sh", "py", "java", "kt", "js", "ts", "css", "sql",
    )

    /**
     * Whether to walk into the folder [name] at [depth] (1 = directly under the storage root).
     * Hidden folders are skipped anywhere; the media and app-data folders only at the top, so a
     * "Music" folder inside Documents is still searched.
     */
    fun walksInto(name: String, depth: Int): Boolean =
        !name.startsWith(".") && !(depth == 1 && name.lowercase() in SKIPPED_TOP)

    /** Whether a file is indexed at all (by name). */
    fun indexes(name: String): Boolean = !name.startsWith(".")

    /** Whether the file's text is read as well as its name. */
    fun readsContent(name: String, sizeBytes: Long): Boolean =
        sizeBytes in 1..MAX_CONTENT_BYTES && name.substringAfterLast('.', "").lowercase() in CONTENT_EXTENSIONS

    /** The stored text: collapsed whitespace, at most [MAX_TEXT_CHARS]. */
    fun storedText(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT_CHARS)
}
