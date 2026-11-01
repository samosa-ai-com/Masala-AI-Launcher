package com.samosaai.masala.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.halilibo.richtext.markdown.Markdown as RichMarkdown
import com.halilibo.richtext.ui.material3.Material3RichText
import com.samosaai.masala.R

/**
 * Chat Markdown: rendered by compose-richtext, with code blocks drawn here (scroll sideways, copy button).
 * [linkColor] replaces the library's default link blue, which is hard to read on glass.
 */
@Composable
fun Markdown(markdown: String, modifier: Modifier = Modifier, linkColor: androidx.compose.ui.graphics.Color? = null) {
    val segments = remember(markdown) { MarkdownParser.segments(markdown) }
    val context = LocalContext.current
    val style = remember(linkColor) {
        linkColor?.let {
            com.halilibo.richtext.ui.RichTextStyle(
                stringStyle = com.halilibo.richtext.ui.string.RichTextStringStyle(
                    linkStyle = SpanStyle(color = it, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                ),
            )
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.flatMap { segment ->
            if (segment is MarkdownSegment.Prose) MarkdownParser.splitTables(segment.markdown) else listOf(segment)
        }.forEach { segment ->
            when (segment) {
                is MarkdownSegment.Prose -> Material3RichText(style = style) { RichMarkdown(segment.markdown, onLinkClicked = { openLink(context, it) }) }
                is MarkdownSegment.Code -> CodeBox(segment.code, segment.language)
                is MarkdownSegment.Table -> MarkdownTable(
                    headers = segment.headers,
                    aligns = segment.aligns,
                    rows = segment.rows,
                )
            }
        }
    }
}

/** A code block: monospace, long lines scroll sideways, with a copy button. */
@Composable
fun CodeBox(code: String, language: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerHighest, contentColor = colors.onSurface) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.orEmpty(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { copyToClipboard(context, "Code", code, R.string.code_copied) }) { Text(stringResource(R.string.code_copy)) }
            }
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

/**
 * Opens a link from model output. The default handler throws when no app takes the link (a relative
 * path, an odd scheme), which would take the launcher down with it.
 */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, R.string.link_cant_open, Toast.LENGTH_SHORT).show()
    }
}

fun copyToClipboard(context: Context, label: String, text: String, toast: Int) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, context.getString(toast), Toast.LENGTH_SHORT).show()
}
