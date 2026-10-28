package com.samosaai.masala.ui.skills

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.samosaai.masala.core.contract.SkillInput
import com.samosaai.masala.skills.Attachment
import java.io.ByteArrayOutputStream
import java.io.File

/** Picked files and photos for a skill run (plan Phase 4 M2). */
object SkillInputs {
    private const val MAX_PHOTO_SIDE = 1024

    /** Inputs this build can take; the rest are shown disabled with a reason. */
    val supported = setOf(SkillInput.TEXT, SkillInput.PHOTO, SkillInput.VIDEO, SkillInput.DOCUMENT, SkillInput.AUDIO_FILE)

    /** Copies a picked [uri] into [dir], so tools get a path inside Masala's storage. */
    fun copyPicked(context: Context, uri: Uri, kind: SkillInput, dir: File): Attachment? = try {
        val name = displayName(context, uri) ?: "file"
        val target = File(dir.apply { mkdirs() }, "${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: return null
        Attachment(kind, target.absolutePath, name)
    } catch (e: Exception) {
        null
    }

    /** The photo at [path] scaled to fit [MAX_PHOTO_SIDE] and as base64 JPEG, for the vision model. */
    fun photoBase64(path: String): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
