package com.samosaai.masala.launcher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import androidx.core.graphics.createBitmap
import com.samosaai.masala.data.IconShape
import com.samosaai.masala.data.IconStyle
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.samosaai.masala.core.apps.AppKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App and shortcut icons, loaded off the main thread the first time a row shows them and kept in an
 * LRU cache sized in bytes (an eighth of the heap). Each is drawn into the chosen [IconMask] (plan UI
 * refresh U1); "tinted" uses the app's monochrome layer on Android 13+. Work-profile icons come badged.
 */
class IconCache(context: Context, private val catalog: AppCatalog) {
    private val res = context.resources
    private val packageManager = context.packageManager
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val density = context.resources.displayMetrics.densityDpi
    private val sizePx = (ICON_DP * context.resources.displayMetrics.density).toInt()

    /** How icons are drawn. Compose state, so rows showing an icon reload when it changes. */
    data class Style(val shape: IconShape = IconShape.SQUIRCLE, val style: IconStyle = IconStyle.ORIGINAL, val tint: Int = 0xFFEBCB80.toInt())

    var style by mutableStateOf(Style())
        private set

    fun applyStyle(next: Style) {
        if (next != style) style = next
    }

    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }

    fun cached(id: String): ImageBitmap? = cache.get(cacheKey(id, style))

    suspend fun app(key: AppKey): ImageBitmap? {
        val user = catalog.info(key)?.user
        return load(key.id, user) { catalog.info(key)?.getIcon(density) }
    }

    suspend fun shortcut(info: ShortcutInfo): ImageBitmap? =
        load(shortcutId(info), info.userHandle) { launcherApps.getShortcutIconDrawable(info, density) }

    /** After installs, updates and removals, so changed icons are read again. */
    fun clear() = cache.evictAll()

    private fun cacheKey(id: String, s: Style) = "$id|${s.shape}|${s.style}|${s.tint}"

    private suspend fun load(id: String, user: UserHandle?, drawable: () -> Drawable?): ImageBitmap? {
        val s = style
        val key = cacheKey(id, s)
        return cache.get(key) ?: withContext(Dispatchers.IO) {
            runCatching {
                drawable()?.let { render(it, s) }?.let { badge(it, user) }
                    // prepareToDraw starts the texture upload now, not on the first frame that shows the icon.
                    ?.also { it.prepareToDraw() }?.asImageBitmap()
            }.getOrNull()
        }?.also { cache.put(key, it) }
    }

    private fun render(drawable: Drawable, s: Style): Bitmap {
        val mono = if (s.style == IconStyle.TINTED && drawable is AdaptiveIconDrawable && Build.VERSION.SDK_INT >= 33) drawable.monochrome else null
        // No mask for as-shipped: the icon untouched, or its monochrome layer on the tint tile,
        // clipped to the same mask the tile next to it uses.
        if (s.shape == IconShape.AS_SHIPPED) {
            if (mono == null) return drawable.toBitmap(sizePx, sizePx)
            val bitmap = createBitmap(sizePx, sizePx)
            val canvas = Canvas(bitmap)
            canvas.clipPath(IconMask.path(s.shape, sizePx.toFloat(), sizePx.toFloat()))
            drawMono(canvas, mono, sizePx, sizePx / 4, s.tint)
            return bitmap
        }
        val size = sizePx
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.clipPath(IconMask.path(s.shape, size.toFloat(), size.toFloat()))
        // Adaptive layers are 108 dp with 72 dp visible: draw them half again as big, centred.
        val pad = size / 4
        if (drawable is AdaptiveIconDrawable) {
            if (mono != null) {
                drawMono(canvas, mono, size, pad, s.tint)
            } else {
                drawable.background?.apply { setBounds(-pad, -pad, size + pad, size + pad); draw(canvas) }
                drawable.foreground?.apply { setBounds(-pad, -pad, size + pad, size + pad); draw(canvas) }
            }
        } else {
            // A legacy icon sits on a light tile inside the same mask.
            canvas.drawColor(LEGACY_TILE)
            val inset = (size * 0.14f).toInt()
            drawable.setBounds(inset, inset, size - inset, size - inset)
            drawable.draw(canvas)
        }
        return bitmap
    }

    /** The monochrome layer in [tint] on the dark tint tile, drawn oversized like the mask path. */
    private fun drawMono(canvas: Canvas, mono: Drawable, size: Int, pad: Int, tint: Int) {
        canvas.drawColor(TINTED_TILE)
        mono.mutate().apply {
            setTint(tint)
            setBounds(-pad, -pad, size + pad, size + pad)
            draw(canvas)
        }
    }

    private fun badge(bitmap: Bitmap, user: UserHandle?): Bitmap {
        if (user == null || user == Process.myUserHandle()) return bitmap
        return packageManager.getUserBadgedIcon(BitmapDrawable(res, bitmap), user).toBitmap(sizePx, sizePx)
    }

    companion object {
        const val ICON_DP = 48
        private const val TINTED_TILE = 0xFF242527.toInt()
        private const val LEGACY_TILE = 0xFFF3F1EC.toInt()

        fun shortcutId(info: ShortcutInfo) = "shortcut:${info.`package`}/${info.id}#${info.userHandle.hashCode()}"
    }
}

@Composable
fun rememberAppIcon(icons: IconCache, key: AppKey): ImageBitmap? {
    // A cached icon needs no coroutine: rows scrolling into view only start one for a missing icon.
    val style = icons.style
    var icon by remember(key, style) { mutableStateOf(icons.cached(key.id)) }
    if (icon == null) LaunchedEffect(key, style) { icon = icons.app(key) }
    return icon
}

@Composable
fun rememberShortcutIcon(icons: IconCache, info: ShortcutInfo): ImageBitmap? {
    // A cached icon needs no coroutine, like rememberAppIcon above.
    val id = IconCache.shortcutId(info)
    val style = icons.style
    var icon by remember(id, style) { mutableStateOf(icons.cached(id)) }
    if (icon == null) LaunchedEffect(id, style) { icon = icons.shortcut(info) }
    return icon
}
