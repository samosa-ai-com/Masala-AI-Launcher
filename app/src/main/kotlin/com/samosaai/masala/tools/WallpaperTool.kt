package com.samosaai.masala.tools

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.samosaai.masala.core.tool.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sets the home-screen wallpaper from an image URL.
 *
 * The image is downloaded to a file and decoded down to the screen size before it reaches
 * `WallpaperManager`: a full-resolution photo decoded whole is what made this tool take the app
 * down with an `OutOfMemoryError`, which is an `Error` and so slipped past a `catch (Exception)`.
 */
class WallpaperTool(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun setWallpaper(url: String?): ActionResult = withContext(Dispatchers.IO) {
        val imageUrl = url?.takeIf { it.isNotBlank() } ?: DEFAULT_RANDOM_URL
        if (!imageUrl.startsWith("https://") && !imageUrl.startsWith("http://")) {
            return@withContext ActionResult.error("'$imageUrl' is not a valid http(s) image URL.")
        }
        val temp = File(context.cacheDir, "wallpaper-download")
        try {
            download(imageUrl, temp)?.let { return@withContext it }
            val bitmap = decodeForScreen(temp)
                ?: return@withContext ActionResult.error(
                    "Downloaded data is not a decodable image. The URL may not point to a valid image file.",
                )
            try {
                WallpaperManager.getInstance(context)
                    .setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
            } finally {
                bitmap.recycle()
            }
            ActionResult.ok("Wallpaper updated from $imageUrl.")
        } catch (e: SecurityException) {
            ActionResult.error("Missing SET_WALLPAPER permission: ${e.message}")
        } catch (e: Throwable) {
            // OutOfMemoryError and friends are why this tool used to crash the app.
            ActionResult.error("Could not set the wallpaper: ${e::class.simpleName}: ${e.message}")
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** Downloads into [target]; returns an error result when that fails, null on success. */
    private fun download(imageUrl: String, target: File): ActionResult? {
        val request = Request.Builder().url(imageUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ActionResult.error(
                    "Image download failed (HTTP ${response.code}) from $imageUrl. You may verify the URL with webfetch first.",
                )
            }
            val body = response.body ?: return ActionResult.error("Empty response from $imageUrl.")
            val length = body.contentLength()
            if (length > MAX_BYTES) {
                return ActionResult.error("That image is too large (${length / 1024 / 1024} MB).")
            }
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        return null
    }

    /** Decodes at a sample size that fits the screen, so peak memory stays a few MB. */
    private fun decodeForScreen(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val (screenW, screenH) = screenSize()
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenW && bounds.outHeight / (sample * 2) >= screenH) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val size = android.graphics.Point()
        wm.defaultDisplay.getSize(size)
        return size.x.coerceAtLeast(1) to size.y.coerceAtLeast(1)
    }

    companion object {
        private const val DEFAULT_RANDOM_URL = "https://picsum.photos/1080/1920"

        /** A wallpaper is a background image, not a raw dump: 20 MB is already generous. */
        private const val MAX_BYTES = 20L * 1024 * 1024
    }
}
