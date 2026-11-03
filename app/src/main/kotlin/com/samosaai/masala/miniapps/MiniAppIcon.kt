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
import com.samosaai.masala.core.layout.IconSpec
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
    const val MAX_ICON_CHARS = IconSpec.MAX_CHARS

    /** What the agent is told when its icon was refused, so it can try again. */
    const val REFUSED_NOTE = "The icon was refused (not a built-in name, or an SVG over 8 KB, not starting with <svg, " +
        "or with scripts, images or links); the app keeps its previous icon or shows its title's letter."

    /** What gets stored; the rules live in [IconSpec], shared with an app's chosen icon. */
    fun sanitize(raw: String?): String? = IconSpec.sanitize(raw)

    /** A line for the tool result when the agent sent an icon that [sanitize] refused; empty otherwise. */
    fun refusedNote(raw: String?): String = if (!raw.isNullOrBlank() && sanitize(raw) == null) "\n" + REFUSED_NOTE else ""
}

/**
 * Any chosen icon — a mini app's, or an app's override: a built-in symbol, a small SVG rendered off
 * the main thread, or [fallbackLabel]'s first letter when there is none or the SVG doesn't render.
 */
@Composable
fun SpecIcon(spec: String?, fallbackLabel: String, size: Dp) {
    val label = fallbackLabel
    val symbol = spec?.takeIf { it.startsWith("symbol:") }?.removePrefix("symbol:")
    if (symbol != null) {
        SymbolDisc(SymbolIcons.icon(symbol), tint = null, size = size.value.roundToInt())
        return
    }
    val svg = spec
    if (svg == null) {
        LetterTile(label, size)
        return
    }
    val px = with(LocalDensity.current) { size.toPx() }.roundToInt().coerceAtLeast(1)
    // The parse and raster stay off the main thread; a new icon or size re-renders.
    val bitmap by produceState<Bitmap?>(null, svg, px) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val doc = SVG.getFromString(svg)
                // Without a viewBox, resizing only crops; take one from its own size so it scales.
                if (doc.documentViewBox == null && doc.documentWidth > 0f && doc.documentHeight > 0f) {
                    doc.setDocumentViewBox(0f, 0f, doc.documentWidth, doc.documentHeight)
                }
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
        // Clipped to the same mask as the app icons beside it, so a pasted SVG sits in a tile rather
        // than floating as a loose shape.
        val setting = LocalContext.current.masala.icons.style.shape
        val mask = remember(setting) { IconMask.shape(if (setting == IconShape.AS_SHIPPED) IconShape.SQUIRCLE else setting) }
        Image(image, contentDescription = null, modifier = Modifier.size(size).clip(mask))
    } else {
        LetterTile(label, size)
    }
}

/** A mini app's icon: the same [SpecIcon], named for the place it came from. */
@Composable
fun MiniAppIconView(icon: String?, title: String, size: Dp) = SpecIcon(icon, title, size)

/** The title's first letter in the same mask as the symbol tiles around it. Decorative. */
@Composable
private fun LetterTile(label: String, size: Dp) {
    val setting = LocalContext.current.masala.icons.style.shape
    val mask = remember(setting) {
        IconMask.shape(if (setting == IconShape.AS_SHIPPED) IconShape.SQUIRCLE else setting)
    }
    val tokens = MasalaTheme.tokens
    val glyph = tokens.accentText
    // Same tile ground as a symbol tile: a wash of the accent over the theme's dark or light base.
    val ground = if (tokens.dark) MasalaColors.Charcoal else MaterialTheme.colorScheme.surfaceVariant
    val fill = remember(glyph, ground) { glyph.copy(alpha = 0.16f).compositeOver(ground) }
    val letter = label.trim().firstOrNull()?.uppercase() ?: "?"
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
