package com.samosaai.masala.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/** From the private prototype's ClipboardTool. */
class ClipboardTool(private val context: Context) {

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Read the current clipboard text. Since Android 14 only the keyboard and the app in focus may
     * read it; the private prototype's accessibility-service fallback isn't part of Masala.
     */
    fun getClipboard(): ActionResult {
        return try {
            val clip = try { clipboard.primaryClip } catch (_: SecurityException) { null }
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                if (text.isNotEmpty()) return ActionResult.ok("Clipboard: ${text.take(2000)}")
            }
            if (Build.VERSION.SDK_INT >= 34) {
                ActionResult.ok(
                    "The clipboard is empty, or Android didn't let Masala read it (Android 14+ allows that " +
                        "only while Masala is on screen). Ask the user to paste the text instead.",
                )
            } else {
                ActionResult.ok("The clipboard is empty.")
            }
        } catch (e: Exception) {
            ActionResult.error("Could not read the clipboard: ${e.message}")
        }
    }

    /** Put text on the clipboard. */
    fun setClipboard(text: String): ActionResult {
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("Masala", text))
            ActionResult.ok("Copied to the clipboard.")
        } catch (e: Exception) {
            ActionResult.error("Could not set the clipboard: ${e.message}")
        }
    }
}
