package com.samosaai.masala.ui.settings

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.samosaai.masala.R
import com.samosaai.masala.masala
import com.samosaai.masala.wallpapers.Wallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Wallpapers (plan `docs/plans/wallpaper-generation.md`): every generated wallpaper
 * with its prompt, searchable. The same screen the home long-press "Change wallpaper" opens.
 * Tap a tile for a big preview with Set (home/lock/both) and Delete.
 */
@Composable
fun WallpapersPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.masala
    val store = app.wallpapers
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Wallpaper>>(emptyList()) }
    var nonce by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Wallpaper?>(null) }
    var confirmDelete by remember { mutableStateOf<Wallpaper?>(null) }
    val deletedText = stringResource(R.string.wallpaper_deleted)

    LaunchedEffect(query, nonce) {
        items = withContext(Dispatchers.IO) { store.search(query) }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    SettingsScaffold(stringResource(R.string.wallpaper_title), onBack) {        OutlinedTextField(
            query, { query = it },
            placeholder = { Text(stringResource(R.string.wallpaper_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.wallpaper_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { w ->
                    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { picked = w }) {
                        WallpaperThumb(w.file.absolutePath, Modifier.fillMaxWidth().aspectRatio(9f / 16f))
                        Text(
                            w.prompt.ifBlank { w.file.name },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    picked?.let { w ->
        Dialog(onDismissRequest = { picked = null }) {
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WallpaperThumb(w.file.absolutePath, Modifier.fillMaxWidth().aspectRatio(9f / 16f), full = true)
                Text(w.prompt.ifBlank { w.file.name }, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        picked = null
                        scope.launch { toast(app.wallpaperTool.setWallpaper(w.file.absolutePath, "home").message) }
                    }) { Text(stringResource(R.string.wallpaper_set_home)) }
                    TextButton(onClick = {
                        picked = null
                        scope.launch { toast(app.wallpaperTool.setWallpaper(w.file.absolutePath, "lock").message) }
                    }) { Text(stringResource(R.string.wallpaper_set_lock)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        picked = null
                        scope.launch { toast(app.wallpaperTool.setWallpaper(w.file.absolutePath, "both").message) }
                    }) { Text(stringResource(R.string.wallpaper_set_both)) }
                    TextButton(onClick = { confirmDelete = w; picked = null }) { Text(stringResource(R.string.wallpaper_delete)) }
                }
            }
        }
    }

    confirmDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.wallpaper_delete_title)) },
            text = { Text(w.prompt.ifBlank { w.file.name }, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(w.id) }
                        nonce++
                        toast(deletedText)
                    }
                }) { Text(stringResource(R.string.wallpaper_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.command_close)) } },
        )
    }
}

/** Sampled thumbnail for a wallpaper file; blank while loading or unreadable. */
@Composable
private fun WallpaperThumb(path: String, modifier: Modifier = Modifier, full: Boolean = false) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { decodeThumb(path, if (full) 1080 else 540) }
    }
    val shape = RoundedCornerShape(14.dp)
    if (bitmap != null) {
        Image(bitmap!!, null, modifier = modifier.clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, shape))
    }
}

private fun decodeThumb(path: String, maxDim: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()
