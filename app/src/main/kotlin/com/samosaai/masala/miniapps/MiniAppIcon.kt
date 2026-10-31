package com.samosaai.masala.miniapps

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import com.samosaai.masala.core.layout.KnownSymbols
import com.samosaai.masala.data.IconShape
import com.samosaai.masala.launcher.IconMask
import com.samosaai.masala.masala
import com.samosaai.masala.ui.home.SymbolDisc
import com.samosaai.masala.ui.theme.MasalaColors
import com.samosaai.masala.ui.home.SymbolIcons
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Agent-chosen icons for mini apps (UI feedback round 2, Batch C): a built-in symbol name or one
 * small inline SVG. SVG never loads anything external: no external file resolver is registered, and
 * [sanitize] refuses remote references before anything is stored.
 */
object MiniAppIcon {
    /** What `create_mini_app`/`update_mini_app` accept as `icon`; anything longer is refused. */
    const val MAX_ICON_CHARS = 8_192

    private val BLOCKED = listOf("<script", "<foreignobject", "<image", "href=\"http", "href='http", "url(http")

    /**
     * What gets stored: `symbol:<name>` for a built-in, the SVG as-is, or null when [raw] is
     * blank, too long, not an SVG, or reaches for anything remote.
     */
    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (!trimmed.startsWith("<")) {
            val name = trimmed.removePrefix("symbol:")
            return if (KnownSymbols.names.contains(name)) "symbol:$name" else null
        }
        if (trimmed.length > MAX_ICON_CHARS) return null
        if (!trimmed.startsWith("<svg")) return null
        if (BLOCKED.any { it in trimmed.lowercase() }) return null
        return trimmed
    }
}

/**
 * A mini app's icon: a built-in symbol, a small SVG rendered off the main thread, or the title's
 * first letter when there is no icon or the SVG doesn't render.
 */
@Composable
fun MiniAppIconView(icon: String?, title: String, size: Dp) {
    val symbol = icon?.takeIf { it.startsWith("symbol:") }?.removePrefix("symbol:")
    if (symbol != null) {
        SymbolDisc(SymbolIcons.icon(symbol), tint = null, size = size.value.roundToInt())
        return
    }
    val svg = icon
    if (svg == null) {
        LetterTile(title, size)
        return
    }
    val px = with(LocalDensity.current) { size.toPx() }.roundToInt().coerceAtLeast(1)
    // The parse and raster stay off the main thread; a new icon or size re-renders.
    val bitmap by produceState<Bitmap?>(null, svg, px) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val doc = SVG.getFromString(svg)
                doc.documentWidth = px.toFloat()
                doc.documentHeight = px.toFloat()
                Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
                    doc.renderToPicture().draw(Canvas(it))
                }
            }.getOrNull()
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    if (image != null) {
        Image(image, contentDescription = null, modifier = Modifier.size(size))
    } else {
        LetterTile(title, size)
    }
}

/** The title's first letter in the same mask as the symbol tiles around it. Decorative. */
@Composable
private fun LetterTile(title: String, size: Dp) {
    val setting = LocalContext.current.masala.icons.style.shape
    val mask = remember(setting) {
        IconMask.shape(if (setting == IconShape.AS_SHIPPED) IconShape.SQUIRCLE else setting)
    }
    val tokens = MasalaTheme.tokens
    val glyph = tokens.accentText
    // Same tile ground as a symbol tile: a wash of the accent over the theme's dark or light base.
    val ground = if (tokens.dark) MasalaColors.Charcoal else MaterialTheme.colorScheme.surfaceVariant
    val fill = remember(glyph, ground) { glyph.copy(alpha = 0.16f).compositeOver(ground) }
    val letter = title.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        Modifier.size(size).clip(mask).background(fill).border(1.dp, tokens.hairline, mask),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            color = glyph,
            fontSize = (size.value * 0.5f).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
