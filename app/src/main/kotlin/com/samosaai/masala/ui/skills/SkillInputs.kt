package com.samosaai.masala.ui.skills

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
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
    val supported = setOf(
        SkillInput.TEXT, SkillInput.PHOTO, SkillInput.VIDEO, SkillInput.DOCUMENT, SkillInput.AUDIO_FILE, SkillInput.FOLDER, SkillInput.PATH,
    )

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Folder and path inputs hand tools real paths in shared storage, which needs All files access. */
    fun needsAllFiles(input: SkillInput): Boolean = input == SkillInput.FOLDER && !Environment.isExternalStorageManager()

    /**
     * The path in shared storage for a document or tree id from the system picker
     * (`primary:Download/Taxes` → `<storage>/Download/Taxes`; the Downloads provider's
     * `raw:/storage/…`). Null for anything else: SD cards, cloud providers, media ids. [storage] is
     * this user's shared storage, `/storage/emulated/<user>`.
     */
    internal fun storagePath(documentId: String, storage: String): String? = when {
        documentId.startsWith("raw:/") -> documentId.removePrefix("raw:")
        documentId == "primary:" -> storage
        documentId.startsWith("primary:") -> "$storage/" + documentId.removePrefix("primary:").trim('/')
        else -> null
    }

    /** A picked folder as its real path, or null when it isn't on this phone's storage. */
    fun pickedFolder(uri: Uri): Attachment? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val storage = storageRoot()
        val path = storagePath(DocumentsContract.getTreeDocumentId(uri), storage)?.takeIf { File(it).isDirectory } ?: return null
        return Attachment(SkillInput.FOLDER, path, if (path == storage) "Storage" else File(path).name)
    }

    /**
     * A picked file for a PATH input: with All files access, a file on this phone's storage keeps its
     * real path, so tools can write results next to it (arch §3.10); otherwise it is copied like any pick.
     */
    fun pickedPath(context: Context, uri: Uri, dir: File): Attachment? {
        val real = if (Environment.isExternalStorageManager() && DocumentsContract.isDocumentUri(context, uri)) {
            storagePath(DocumentsContract.getDocumentId(uri), storageRoot())?.takeIf { File(it).isFile }
        } else {
            null
        }
        return real?.let { Attachment(SkillInput.PATH, it, File(it).name) } ?: copyPicked(context, uri, SkillInput.PATH, dir)
    }

    private fun storageRoot(): String = Environment.getExternalStorageDirectory().absolutePath

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
