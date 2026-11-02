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
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                thumb?.let {
                    Image(
                        it,
                        a.name,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Icon(
                    Icons.Rounded.Close, "Remove",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.TopEnd).clickable { onRemove(a.id) }.padding(2.dp).size(20.dp),
                )
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
