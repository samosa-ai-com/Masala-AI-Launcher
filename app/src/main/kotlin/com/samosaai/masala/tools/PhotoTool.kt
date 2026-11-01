package com.samosaai.masala.tools

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.samosaai.masala.core.tool.ActionResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Takes a photo through the camera app: no CAMERA permission, because the camera app (not Masala)
 * takes the picture. The photo is saved to Masala's work folder and the answer names it, so a
 * later turn can look at it with `read_file`.
 */
class PhotoTool(private val context: Context) {

    fun takePhoto(): ActionResult {
        return try {
            val file = photoFile(workDir())
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                // The grant flag does not cover extras: the ClipData carries the write grant.
                clipData = ClipData.newUri(context.contentResolver, "photo", uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.ok(
                "Opened the camera. The photo goes to '${file.name}' (work/${file.name}) once the user takes it; read it with read_file on a later turn.",
            )
        } catch (_: ActivityNotFoundException) {
            ActionResult.error("No camera app on this phone handles photos.")
        } catch (e: IllegalArgumentException) {
            ActionResult.error("Could not prepare the photo file: ${e.message}")
        }
    }

    private fun workDir(): File = File(context.filesDir, "work").apply { mkdirs() }

    internal companion object {
        /** "photo-20261101-093000.jpg": sorts by time and never collides within a second. */
        fun photoFile(dir: File): File {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            return File(dir, "photo-$stamp.jpg")
        }
    }
}
