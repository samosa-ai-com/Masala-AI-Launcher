package com.samosaai.masala.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * One image queued in the chat composer, or carried by a sent turn (plan
 * `docs/plans/chat-attachments.md`). Images are downscaled to JPEG base64 on the
 * way in; documents are out of scope.
 */
data class AttachedImage(
    val id: String,
    val name: String,
    val jpegBase64: String,
)

/** Images per message: three 1024px JPEGs stay far below every request-size cap. */
const val MAX_IMAGES_PER_MESSAGE = 3

/** Transcript label for an image-only prompt, so history and compaction keep a marker. */
const val IMAGE_PLACEHOLDER = "(image attached)"

/** App-private folder (under `filesDir`) holding per-message attachment images for the media tools. */
const val CHAT_ATTACHMENTS_DIR = "chat_attachments"

/** How long orphaned attachment images live before the startup sweep removes them. */
const val ATTACHMENT_RETENTION_MS = 14L * 24 * 60 * 60 * 1000

/**
 * One line per image, appended to the text the model sees so path-taking tools
 * (`segment_image`, `edit_image`) can read the attached photo. Compact on purpose:
 * every line costs context on every later turn until compaction.
 */
fun imagePathLine(index: Int, total: Int, path: String) = "[image ${index + 1} of $total: $path]"

/**
 * The user's text plus one [imagePathLine] per materialized image. The transcript keeps
 * its placeholder; only the model text carries paths.
 */
fun modelTextWithImagePaths(prompt: String, paths: List<String>): String {
    if (paths.isEmpty()) return prompt
    val lines = paths.mapIndexed { i, path -> imagePathLine(i, paths.size, path) }.joinToString("\n")
    return if (prompt.isBlank()) lines else "$prompt\n$lines"
}

/**
 * Writes each base64 image under `filesDir/chat_attachments/<messageId>/<n>.jpg` and returns
 * the absolute paths, in order. Undecodable entries are skipped, never fatal: the turn still
 * sends its vision parts. Off the main thread; `java.util.Base64` (not the Android one) so
 * JVM unit tests can cover it. Callers persist the paths on the turn for reopened chats.
 */
suspend fun materializeAttachments(filesDir: File, messageId: String, imagesBase64: List<String>): List<String> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (imagesBase64.isEmpty()) return@withContext emptyList()
        val safe = messageId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { "msg" }
        val dir = File(File(filesDir, CHAT_ATTACHMENTS_DIR), safe)
        val out = mutableListOf<String>()
        imagesBase64.forEachIndexed { i, b64 ->
            val bytes = runCatching { java.util.Base64.getDecoder().decode(b64.trim()) }.getOrNull()
                ?: return@forEachIndexed
            if (bytes.isEmpty()) return@forEachIndexed
            runCatching {
                dir.mkdirs()
                val file = File(dir, "$i.jpg")
                file.writeBytes(bytes)
                out += file.absolutePath
            }
        }
        out.toList()
    }

/**
 * Deletes only [paths] that live under `filesDir/chat_attachments`, then prunes the message
 * folders left empty. Anything outside is left alone: a stored path must never become a
 * deletion primitive.
 */
fun deleteAttachmentFiles(filesDir: File, paths: List<String>) {
    val root = runCatching { File(filesDir, CHAT_ATTACHMENTS_DIR).canonicalFile }.getOrNull() ?: return
    paths.forEach { path ->
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return@forEach
        if (!file.path.startsWith(root.path + File.separator)) return@forEach
        runCatching {
            file.delete()
            var parent = file.parentFile
            while (parent != null && parent != root && parent.path.startsWith(root.path + File.separator)) {
                if (parent.list()?.isNotEmpty() == true) break
                parent.delete()
                parent = parent.parentFile
            }
        }
    }
}

/**
 * Startup sweep: drops `chat_attachments` subfolders older than [maxAgeMs] (a chat delete
 * removes its own files at once; this catches reverted and pruned turns). Returns how many
 * folders went. Never touches anything outside the attachments dir.
 */
fun sweepOldAttachments(
    filesDir: File,
    nowMs: Long = System.currentTimeMillis(),
    maxAgeMs: Long = ATTACHMENT_RETENTION_MS,
): Int {
    val root = File(filesDir, CHAT_ATTACHMENTS_DIR)
    if (!root.isDirectory) return 0
    val rootPath = runCatching { root.canonicalFile.path }.getOrNull() ?: return 0
    var removed = 0
    root.listFiles { f -> f.isDirectory }?.forEach { dir ->
        val path = runCatching { dir.canonicalFile.path }.getOrNull() ?: return@forEach
        if (!path.startsWith(rootPath + File.separator)) return@forEach
        if (nowMs - dir.lastModified() < maxAgeMs) return@forEach
        if (dir.deleteRecursively()) removed++
    }
    return removed
}

/**
 * Downscales [uri] to a JPEG base64 string (long side [maxSide], quality 80).
 * Bounds and pixels use separate streams: a camera photo must never sit whole in heap.
 * Null when the content isn't a readable image.
 */
fun compressToJpegBase64(context: Context, uri: Uri, maxSide: Int = 1024): String? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
    val bmp = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val scaled = if (maxOf(bmp.width, bmp.height) > maxSide) {
        val s = maxSide / maxOf(bmp.width, bmp.height).toFloat()
        android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
    } else bmp
    val bos = ByteArrayOutputStream()
    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
    if (scaled !== bmp) scaled.recycle()
    if (!bmp.isRecycled) bmp.recycle()
    android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
}.getOrNull()

/** Thumbnails of what's queued, each with a remove button. Decodes small thumbs off the main thread. */
@Composable
fun AttachmentStrip(
    attachments: List<AttachedImage>,
    onRemove: (String) -> Unit,
) {
    if (attachments.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { a ->
            var thumb by androidx.compose.runtime.remember(a.id) {
                androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
            }
            androidx.compose.runtime.LaunchedEffect(a.id) {
                thumb = withContextThumb(a.jpegBase64)
            }
            // The thumbnail with a round remove badge over its corner (plan reply-sheet-refresh).
            Box(Modifier.size(64.dp)) {
                Box(Modifier.align(Alignment.BottomStart).size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    thumb?.let {
                        Image(
                            it,
                            a.name,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).size(24.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { onRemove(a.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        androidx.compose.ui.res.stringResource(com.samosaai.masala.R.string.chat_remove_image),
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

private suspend fun withContextThumb(base64: String): androidx.compose.ui.graphics.ImageBitmap? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 128 || bounds.outHeight / (sample * 2) >= 128) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }
