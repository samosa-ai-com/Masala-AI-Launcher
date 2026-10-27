package com.samosaai.masala.launcher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.samosaai.masala.core.apps.AppKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App and shortcut icons, loaded off the main thread the first time a row shows them and kept in an
 * LRU cache sized in bytes (an eighth of the heap). Work-profile icons come badged.
 */
class IconCache(context: Context, private val catalog: AppCatalog) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val density = context.resources.displayMetrics.densityDpi
    private val sizePx = (ICON_DP * context.resources.displayMetrics.density).toInt()

    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }

    fun cached(id: String): ImageBitmap? = cache.get(id)

    suspend fun app(key: AppKey): ImageBitmap? =
        load(key.id) { catalog.info(key)?.getBadgedIcon(density) }

    suspend fun shortcut(info: ShortcutInfo): ImageBitmap? =
        load(shortcutId(info)) { launcherApps.getShortcutBadgedIconDrawable(info, density) }

    /** After installs, updates and removals, so changed icons are read again. */
    fun clear() = cache.evictAll()

    private suspend fun load(id: String, drawable: () -> Drawable?): ImageBitmap? =
        cache.get(id) ?: withContext(Dispatchers.IO) {
            runCatching { drawable()?.toBitmap(sizePx, sizePx)?.asImageBitmap() }.getOrNull()
        }?.also { cache.put(id, it) }

    companion object {
        const val ICON_DP = 48

        fun shortcutId(info: ShortcutInfo) = "shortcut:${info.`package`}/${info.id}#${info.userHandle.hashCode()}"
    }
}

@Composable
fun rememberAppIcon(icons: IconCache, key: AppKey): ImageBitmap? {
    val icon by produceState(icons.cached(key.id), key) { value = icons.app(key) }
    return icon
}

@Composable
fun rememberShortcutIcon(icons: IconCache, info: ShortcutInfo): ImageBitmap? {
    val icon by produceState(icons.cached(IconCache.shortcutId(info)), IconCache.shortcutId(info)) {
        value = icons.shortcut(info)
    }
    return icon
}
