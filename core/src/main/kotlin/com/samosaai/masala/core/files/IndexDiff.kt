package com.samosaai.masala.core.files

/**
 * What an index run has to do, from the files on disk and what is stored (both path → modified
 * time): read the [added] and [changed] ones, drop the [gone] ones, leave the rest.
 */
data class IndexDiff(
    val added: List<String>,
    val changed: List<String>,
    val gone: List<String>,
    val unchanged: Int,
) {
    /** Files to read, new ones first. */
    val toRead: List<String> get() = added + changed

    companion object {
        fun of(onDisk: Map<String, Long>, stored: Map<String, Long>): IndexDiff {
            val added = mutableListOf<String>()
            val changed = mutableListOf<String>()
            var unchanged = 0
            for ((path, modified) in onDisk) {
                when (stored[path]) {
                    null -> added += path
                    modified -> unchanged++
                    else -> changed += path
                }
            }
            val gone = stored.keys.filter { it !in onDisk }
            return IndexDiff(added.sorted(), changed.sorted(), gone.sorted(), unchanged)
        }
    }
}
