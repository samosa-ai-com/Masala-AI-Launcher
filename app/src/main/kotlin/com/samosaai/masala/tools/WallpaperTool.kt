package com.samosaai.masala.tools

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.samosaai.masala.R
import com.samosaai.masala.core.tool.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sets the wallpaper from anything the user points at — a link, a `content://` URI from a picker, a
 * `file://` path or a bare path.
 *
 * The image covers the screen (centre-crop): it is scaled to fill the screen and the overflow is
 * cropped symmetrically, so there are never bars. A portrait photo on a tall screen keeps its
 * full height and loses its sides.
 * Delivery goes through [WallpaperManager.setStream] with a compressed JPEG:
 * `setBitmap` pushes the bitmap across Binder and fails on high-DPI screens.
 */
class WallpaperTool(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun setWallpaper(source: String?, target: String? = null): ActionResult = withContext(Dispatchers.IO) {
        val manager = WallpaperManager.getInstance(context)
        // A blocked device is reported before any download, but a device that *misreports* support
        // (some images and OEM builds answer false and then accept the wallpaper) still gets a try;
        // the attempt itself reports the real reason.
        if (!manager.isSetWallpaperAllowed) {
            return@withContext ActionResult.error(
                "Wallpaper changes are blocked on this device by a device policy or parental control.",
            )
        }

        val temp = mutableListOf<File>()
        try {
            val file = materialize(source?.takeIf { it.isNotBlank() } ?: DEFAULT_RANDOM_URL, temp)
                ?: return@withContext ActionResult.error(
                    "Couldn't read the image from '${source ?: "the default source"}'. Check that the " +
                        "file exists and that Masala may read it.",
                )

            val fitted = composeForScreen(file)
                ?: return@withContext ActionResult.error(
                    "'${file.name}' isn't an image Masala can decode (unsupported or corrupted file).",
                )
            val jpeg = File.createTempFile("wallpaper-out-", ".jpg", context.cacheDir).also { temp += it }
            val wrote = runCatching {
                jpeg.outputStream().use { out -> fitted.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            }.getOrDefault(false)
            val (width, height) = fitted.let { it.width to it.height }
            fitted.recycle()
            if (!wrote) return@withContext ActionResult.error("Couldn't encode the wallpaper image.")

            val flags = when (target?.lowercase()) {
                "lock" -> WallpaperManager.FLAG_LOCK
                "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                else -> WallpaperManager.FLAG_SYSTEM
            }
            jpeg.inputStream().use { manager.setStream(it, null, true, flags) }

            val where = when {
                flags == WallpaperManager.FLAG_LOCK -> "the lock screen"
                flags == (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) -> "home and lock screens"
                else -> "the home screen"
            }
            ActionResult.ok("Wallpaper set for $where at ${width}x$height, center-cropped to fill.")
        } catch (e: SecurityException) {
            ActionResult.error("Masala isn't allowed to set the wallpaper: ${e.message}")
        } catch (e: OutOfMemoryError) {
            ActionResult.error("That image is too large to process safely. Try a smaller one.")
        } catch (e: Throwable) {
            ActionResult.error("Couldn't set the wallpaper: ${e::class.simpleName}: ${e.message}")
        } finally {
            temp.forEach { runCatching { it.delete() } }
        }
    }

    /** Copies a wallpaper file into the gallery (Pictures/Masala), for keeps. */
    suspend fun saveToGallery(path: String): ActionResult = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.canRead()) return@withContext ActionResult.error("Couldn't read '${file.name}'.")
        // Name and type follow the bytes: generations and bundled art are WebP.
        val webp = file.extension.equals("webp", ignoreCase = true)
        val ext = if (webp) ".webp" else ".png"
        runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file.nameWithoutExtension.removePrefix("bundled-") + ext)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, if (webp) "image/webp" else "image/png")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Masala")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("No slot")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("No stream")
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        }.fold(
            onSuccess = { ActionResult.ok(context.getString(R.string.wallpaper_saved_gallery)) },
            onFailure = { ActionResult.error("Couldn't save to the gallery: ${it.message}") },
        )
    }

    /**
     * Turns [source] into a readable local file: downloads a URL, copies content URIs out of the
     * provider (their permission may lapse), and resolves file:// and bare paths.
     */
    private fun materialize(source: String, temp: MutableList<File>): File? {
        val lower = source.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> download(source, temp)
            lower.startsWith("content://") -> runCatching {
                val target = File.createTempFile("wallpaper-in-", null, context.cacheDir).also { temp += it }
                context.contentResolver.openInputStream(Uri.parse(source))?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                target.takeIf { it.length() > 0 }
            }.getOrNull()
            lower.startsWith("file://") -> File(Uri.parse(source).path ?: return null).takeIf { it.canRead() }
            source.startsWith("/") -> resolveBarePath(source)?.takeIf { it.canRead() }
            else -> null
        }
    }

    /** `/sdcard/...` is not the real path on Android; both spellings are accepted here. */
    private fun resolveBarePath(path: String): File? {
        val direct = File(path)
        if (direct.canRead()) return direct
        val external = Environment.getExternalStorageDirectory()?.absolutePath
        if (external != null && (path.startsWith("/sdcard") || path.startsWith("/storage/self/primary"))) {
            val rest = path.removePrefix("/sdcard").removePrefix("/storage/self/primary")
            return File(external + rest)
        }
        return direct
    }

    private fun download(url: String, temp: MutableList<File>): File? = runCatching {
        val target = File.createTempFile("wallpaper-in-", null, context.cacheDir).also { temp += it }
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_DOWNLOAD_BYTES) return null
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        target.takeIf { it.length() > 0 }
    }.getOrNull()

    /**
     * Decodes to screen size, honouring EXIF rotation, then draws the image covering a screen-sized
     * canvas (centre-crop): the scale covers the screen on both axes and the overflow is cropped
     * symmetrically, so there are never bars. Wide and square images lose their sides, but a
     * wallpaper with bars reads as broken.
     */
    private fun composeForScreen(file: File): Bitmap? {
        val (screenW, screenH) = screenSize()
        val orientation = runCatching {
            file.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotated = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val rawW = if (rotated) bounds.outHeight else bounds.outWidth
        val rawH = if (rotated) bounds.outWidth else bounds.outHeight

        var sample = 1
        while (rawW / (sample * 2) >= screenW && rawH / (sample * 2) >= screenH) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        val upright = applyExif(decoded, orientation).also { if (it !== decoded) decoded.recycle() }

        val out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // Fill: the smaller of the two ratios would letterbox; the larger covers the screen and
        // the overflow is cropped by the centred destination rect.
        val scale = maxOf(screenW.toFloat() / upright.width, screenH.toFloat() / upright.height)
        val drawW = (upright.width * scale).toInt().coerceAtLeast(1)
        val drawH = (upright.height * scale).toInt().coerceAtLeast(1)
        val dst = Rect((screenW - drawW) / 2, (screenH - drawH) / 2, (screenW + drawW) / 2, (screenH + drawH) / 2)
        canvas.drawBitmap(upright, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        upright.recycle()
        return out
    }


    private fun applyExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
            .getOrDefault(bitmap)
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
        } else {
            @Suppress("DEPRECATION")
            val size = android.graphics.Point().also { wm.defaultDisplay.getSize(it) }
            size.x.coerceAtLeast(1) to size.y.coerceAtLeast(1)
        }
    }

    companion object {
        private const val DEFAULT_RANDOM_URL = "https://picsum.photos/1080/1920"

        /** A wallpaper is a background image, not a raw dump. */
        private const val MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024
    }
}
