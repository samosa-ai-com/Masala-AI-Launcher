package com.samosaai.masala.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samosaai.masala.R
import com.samosaai.masala.chat.ChatSummary
import com.samosaai.masala.ui.theme.MasalaTheme
import java.text.DateFormat
import java.util.Date

/**
 * The conversation list (plan `chat-live-sessions`): newest first, one row per session
 * with its title, when it last changed and its last line. Tapping resumes it; the trash
 * deletes it. Deliberately plain — this is a switchboard, not a screen to linger on.
 */
@Composable
fun SessionListSheet(
    sessions: List<ChatSummary>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val format = androidx.compose.runtime.remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }
    // Deleting is one tap away from opening, so it asks first.
    val confirming = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Box(modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 560.dp)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Color(0xFF181A22))
                .navigationBarsPadding()
                // Swallows taps so the scrim's dismiss doesn't fire through the sheet.
                .clickable(enabled = false, onClick = {})
                .padding(vertical = 12.dp),
        ) {
            Text(
                stringResource(R.string.chat_sessions_title),
                style = MaterialTheme.typography.titleMedium,
                color = MasalaTheme.tokens.textHigh,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.chat_no_sessions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MasalaTheme.tokens.textMedium,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(sessions, key = { it.id }) { s ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(role = Role.Button) { onOpen(s.id) }
                                .padding(start = 20.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.title.ifBlank { stringResource(R.string.chat_new) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MasalaTheme.tokens.textHigh,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${format.format(Date(s.updatedAt))} · ${s.turns} · ${s.preview}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MasalaTheme.tokens.textMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp,
                                )
                            }
                            IconButton(onClick = { confirming.value = s.id }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.chat_delete), tint = MasalaTheme.tokens.textLow)
                            }
                        }
                    }
                }
            }
        }
    }
    confirming.value?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming.value = null },
            title = { Text(stringResource(R.string.chat_delete_title)) },
            text = { Text(stringResource(R.string.chat_delete_message)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirming.value = null
                    onDelete(id)
                }) { Text(stringResource(R.string.chat_delete)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirming.value = null }) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
    }
}
