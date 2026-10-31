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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samosaai.masala.R
import com.samosaai.masala.chat.ChatSummary
import com.samosaai.masala.ui.theme.MasalaTheme
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * The conversation list (plan `chat-live-sessions`): searchable, grouped by when it last
 * changed, newest first. Tapping resumes a chat; the row offers rename and delete.
 */
@Composable
fun SessionListSheet(
    sessions: List<ChatSummary>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirming by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ChatSummary?>(null) }

    val matching = remember(sessions, query) {
        val q = query.trim()
        if (q.isEmpty()) sessions
        else sessions.filter { it.title.contains(q, ignoreCase = true) || it.search.contains(q, ignoreCase = true) }
    }
    // Date groups, as a chat list reads: what changed today, then backwards.
    val groups = remember(matching) { group(matching) }

    Box(modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 620.dp)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Color(0xFF181A22))
                // Search opens the keyboard: the list must stay above it.
                .imePadding()
                .navigationBarsPadding()
                // Swallows taps so the scrim's dismiss doesn't fire through the sheet.
                .clickable(enabled = false, onClick = {})
                .padding(top = 12.dp, bottom = 8.dp),
        ) {
            Text(
                stringResource(R.string.chat_sessions_title),
                style = MaterialTheme.typography.titleMedium,
                color = MasalaTheme.tokens.textHigh,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text(stringResource(R.string.chat_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.chat_clear_search))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (matching.isEmpty()) {
                Text(
                    stringResource(if (query.isBlank()) R.string.chat_no_sessions else R.string.chat_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MasalaTheme.tokens.textMedium,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    groups.forEach { (group, rows) ->
                        item(key = "h-$group") {
                            Text(
                                stringResource(
                                    when (group) {
                                        Group.TODAY -> R.string.chat_group_today
                                        Group.YESTERDAY -> R.string.chat_group_yesterday
                                        Group.WEEK -> R.string.chat_group_week
                                        Group.OLDER -> R.string.chat_group_older
                                    },
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MasalaTheme.tokens.textLow,
                                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(rows, key = { it.id }) { s ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(role = Role.Button) { onOpen(s.id) }
                                    .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                        "${time.format(Date(s.updatedAt))} · ${s.turns} · ${s.preview}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MasalaTheme.tokens.textMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp,
                                    )
                                }
                                IconButton(onClick = { renaming = s }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Rounded.Edit, stringResource(R.string.chat_rename), tint = MasalaTheme.tokens.textLow)
                                }
                                IconButton(onClick = { confirming = s.id }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.chat_delete), tint = MasalaTheme.tokens.textLow)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { s ->
        var text by remember(s.id) { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.chat_rename)) },
            text = {
                OutlinedTextField(
                    text, { input -> text = input },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    onRename(s.id, text)
                }) { Text(stringResource(R.string.chat_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_back)) }
            },
        )
    }

    confirming?.let { id ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.chat_delete_title)) },
            text = { Text(stringResource(R.string.chat_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onDelete(id)
                }) { Text(stringResource(R.string.chat_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_back)) }
            },
        )
    }
}

private enum class Group { TODAY, YESTERDAY, WEEK, OLDER }

/** Today · Yesterday · Previous 7 days · Older, each keeping the newest-first order. */
private fun group(sessions: List<ChatSummary>): List<Pair<Group, List<ChatSummary>>> {
    val now = Calendar.getInstance()
    val startOfToday = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val startOfYesterday = startOfToday - 24 * 60 * 60 * 1000L
    val weekAgo = startOfToday - 7 * 24 * 60 * 60 * 1000L
    val buckets = linkedMapOf(
        Group.TODAY to mutableListOf<ChatSummary>(),
        Group.YESTERDAY to mutableListOf(),
        Group.WEEK to mutableListOf(),
        Group.OLDER to mutableListOf(),
    )
    sessions.forEach { s ->
        val bucket = when {
            s.updatedAt >= startOfToday -> Group.TODAY
            s.updatedAt >= startOfYesterday -> Group.YESTERDAY
            s.updatedAt >= weekAgo -> Group.WEEK
            else -> Group.OLDER
        }
        buckets.getValue(bucket) += s
    }
    return buckets.filterValues { it.isNotEmpty() }.map { it.key to it.value }
}
