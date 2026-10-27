package com.samosaai.masala.tools

import com.samosaai.masala.core.tool.ActionResult
import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
        try {
            val request = Request.Builder().url(imageUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ActionResult.error(
                        "Image download failed (HTTP ${response.code}) from $imageUrl. You may verify the URL with webfetch first."
                    )
                }
                val body = response.body
                    ?: return@withContext ActionResult.error("Empty response from $imageUrl. You may try a different image URL.")
                val bitmap = BitmapFactory.decodeStream(body.byteStream())
                    ?: return@withContext ActionResult.error(
                        "Downloaded data is not a decodable image. The URL may not point to a valid " +
                            "image file."
                    )
                WallpaperManager.getInstance(context).setBitmap(bitmap)
            }
            ActionResult.ok("Wallpaper updated from $imageUrl.")
        } catch (e: SecurityException) {
            ActionResult.error("Missing SET_WALLPAPER permission: ${e.message}")
        } catch (e: Exception) {
            ActionResult.error("Could not set wallpaper: ${e.message}")
        }
    }

    companion object {
        private const val DEFAULT_RANDOM_URL = "https://picsum.photos/1080/1920"
    }
}
