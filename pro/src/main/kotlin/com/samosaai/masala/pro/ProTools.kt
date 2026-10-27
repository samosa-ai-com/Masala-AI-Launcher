package com.samosaai.masala.pro

import android.content.Context
import com.samosaai.masala.core.tool.ToolRegistry

/** The only entry point `:app` may use in `:pro` (plan 0.3). */
object ProTools {
    /**
     * Adds the Play-restricted tools to [registry] and swaps in all-files access. Phase 1, lane C fills
     * this in: the private prototype's `FileResolver` as the `FileAccess`, the file tools, the Termux bridge for
     * ffmpeg and the `PackageInstaller` code.
     */
    @Suppress("UNUSED_PARAMETER")
    fun register(registry: ToolRegistry, context: Context) = Unit
}
