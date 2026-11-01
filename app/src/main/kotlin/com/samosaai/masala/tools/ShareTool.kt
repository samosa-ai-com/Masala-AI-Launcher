package com.samosaai.masala.tools

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.ToolFiles
import java.io.File

/**
 * Sends a file to the share sheet: the user picks where it goes (a chat, Drive, an email draft).
 * Nothing leaves the phone without that tap. Shares anything the file tools can read: app
 * storage and picked files, or anywhere once `:pro` adds all-files access.
 *
 * Validation runs here (JVM-tested); firing the sheet is [fireSheet], injected so tests never
 * touch Android.
 */
class ShareTool(private val files: ToolFiles, private val fire: (File) -> ActionResult) {

    fun share(path: String?): ActionResult {
        if (path.isNullOrBlank()) return ActionResult.error("Pass the file path to share.")
        val resolved = files.resolveForRead(path)
        val file = when (resolved) {
            is ToolFiles.ResolveResult.PermissionNeeded -> return resolved.result
            is ToolFiles.ResolveResult.Error -> return ActionResult.error(resolved.message)
            is ToolFiles.ResolveResult.Ok -> resolved.file
        }
        if (!file.exists()) {
            return ActionResult.error(
                "Path '$path' does not exist (resolved: ${file.canonicalPath}). You may use list_files or glob to discover valid paths.",
            )
        }
        if (!file.isFile) return ActionResult.error("Path '$path' is a directory, not a file.")
        val perm = files.checkReadPermission(file)
        if (perm != null) return perm
        return fire(file)
    }

    companion object {
        fun fireSheet(context: Context): (File) -> ActionResult = { file ->
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    // Some share targets ignore the grant flag unless the ClipData carries it.
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (chooser.resolveActivity(context.packageManager) == null) {
                    ActionResult.error("This phone has nothing to share '${file.name}' with.")
                } else {
                    context.startActivity(chooser)
                    ActionResult.ok("Opened the share sheet for '${file.name}'. The user picks where it goes.")
                }
            } catch (_: ActivityNotFoundException) {
                ActionResult.error("This phone has nothing to share '${file.name}' with.")
            } catch (e: IllegalArgumentException) {
                ActionResult.error("Could not share '${file.name}': ${e.message}")
            }
        }
    }
}
