package com.samosaai.masala.core.files

/**
 * The full-text index of shared storage behind `file_search` (arch §3.10), as `:app` sees it:
 * `:pro` implements it and hands it back from `ProTools.register`; a build without `:pro` has none.
 */
interface FileIndex {
    /** The latest status; [observe] reports every change after this one. */
    val status: IndexStatus

    /** Calls [listener] on each status change until the returned function is called. */
    fun observe(listener: (IndexStatus) -> Unit): () -> Unit

    /** Daily runs while charging, on or off. */
    fun setEnabled(enabled: Boolean)

    /** One run now, charging or not. */
    fun indexNow()

    /** Drops everything indexed; the next run starts over. */
    fun clear()
}

data class IndexStatus(
    val state: State,
    val enabled: Boolean = false,
    /** While [State.RUNNING]: files looked at so far, out of [total] (0 while still counting). */
    val done: Int = 0,
    val total: Int = 0,
    val files: Int = 0,
    val withText: Int = 0,
    val failures: Int = 0,
    val lastRunAt: Long? = null,
    val message: String? = null,
) {
    enum class State {
        /** All-files access isn't granted, so there is nothing it may read. */
        NEEDS_ACCESS,
        IDLE,
        RUNNING,
    }
}
