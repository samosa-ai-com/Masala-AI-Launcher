package com.samosaai.masala.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.samosaai.masala.R

/** Chat Markdown (see [MarkdownParser]); code blocks scroll sideways and have a copy button. */
@Composable
fun Markdown(markdown: String, modifier: Modifier = Modifier) {
    val segments = remember(markdown) { MarkdownParser.segments(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is MarkdownSegment.Prose -> ProseBlocks(segment.markdown)
                is MarkdownSegment.Code -> CodeBox(segment.code, segment.language)
            }
        }
    }
}

@Composable
private fun ProseBlocks(prose: String) {
    val blocks = remember(prose) { MarkdownParser.blocks(prose) }
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    styled(block.spans, codeBackground),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is MdBlock.Paragraph -> Text(styled(block.spans, codeBackground), style = MaterialTheme.typography.bodyLarge)
                is MdBlock.Bullet -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodyLarge)
                    Text(styled(block.spans, codeBackground), style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Numbered -> Row {
                    Text("${block.number}.  ", style = MaterialTheme.typography.bodyLarge)
                    Text(styled(block.spans, codeBackground), style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Quote -> Text(
                    styled(block.spans, codeBackground),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

private fun styled(spans: List<MdSpan>, codeBackground: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
        )
        withStyle(style) { append(span.text) }
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

fun copyToClipboard(context: Context, label: String, text: String, toast: Int) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, context.getString(toast), Toast.LENGTH_SHORT).show()
}
