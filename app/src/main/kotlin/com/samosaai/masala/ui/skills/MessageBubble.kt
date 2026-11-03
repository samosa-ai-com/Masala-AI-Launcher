package com.samosaai.masala.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.skills.SkillRunEvent
import com.samosaai.masala.ui.components.Markdown
import com.samosaai.masala.ui.components.copyToClipboard

/**
 * One message in the sheet: the user's in a bubble, the assistant's as Markdown on the surface with
 * Copy and Report (store policy: AI output can be flagged).
 */
@Composable
fun MessageBubble(message: SkillRunEvent.Message, onReport: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    when (message.role) {
        SkillRunEvent.Message.Role.USER -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            Surface(color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)) {
                Text(message.text, Modifier.widthIn(max = 320.dp).padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        SkillRunEvent.Message.Role.ASSISTANT -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Markdown(message.text)
            val context = LocalContext.current
            Row {
                TextButton(onClick = { copyToClipboard(context, "Masala", message.text, R.string.message_copied) }) { Text(stringResource(R.string.message_copy)) }
                TextButton(onClick = { onReport(message.text) }) { Text(stringResource(R.string.report_action)) }
            }
        }
    }
}
