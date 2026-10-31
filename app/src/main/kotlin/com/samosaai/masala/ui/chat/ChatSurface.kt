package com.samosaai.masala.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samosaai.masala.R
import com.samosaai.masala.agent.CommandState
import com.samosaai.masala.chat.ChatSession
import com.samosaai.masala.chat.ChatTurn
import com.samosaai.masala.chat.TurnRole
import com.samosaai.masala.data.Character
import com.samosaai.masala.ui.character.CharacterState
import com.samosaai.masala.ui.character.MasalaCharacter
import com.samosaai.masala.ui.home.MicPress
import com.samosaai.masala.ui.home.MicButton
import com.samosaai.masala.ui.theme.MasalaTheme

/**
 * The conversation surface (plan `chat-live-sessions`): one panel that is a big
 * amplitude orb while you talk and a scrolled thread the rest of the time. Dragging the
 * handle swaps between the two — down for the thread, up for the orb — and neither state
 * throws the conversation away. Home stays visible behind it.
 */
@Composable
fun ChatSurface(
    session: ChatSession?,
    liveWords: String,
    level: () -> Float,
    state: CommandState,
    character: Character,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    mic: MicPress,
    onSend: () -> Unit,
    onClose: () -> Unit,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MasalaTheme.tokens
    val listening = state == CommandState.Listening
    val thinking = state is CommandState.Thinking
    val turns = session?.turns.orEmpty()
    // A newly stored turn pulls the thread up on its own; talking shows the orb. Keyed on the
    // last turn, not on size alone, so a collapse the user made stays collapsed.
    val lastTurn = turns.lastOrNull()
    LaunchedEffect(lastTurn) {
        if (!listening && lastTurn != null) onExpanded(true)
    }
    val threadHeight by animateFloatAsState(if (expanded) 0.94f else 0.52f, spring(stiffness = 380f), label = "sheet")

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Dims home so the conversation reads over any wallpaper, then the panel itself
        // is opaque: nothing of the launcher shows through a bubble.
        Box(Modifier.fillMaxSize().background(Color(0x8A000000)))
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).fillMaxSize(threadHeight).navigationBarsPadding()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color(0xFF15171E))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        ) {
            Column(Modifier.fillMaxSize()) {
                Header(
                    title = session?.title?.ifBlank { null } ?: stringResource(R.string.chat_new),
                    expanded = expanded,
                    onClose = onClose,
                    onHistory = onHistory,
                    onNewChat = onNewChat,
                    onToggle = { onExpanded(!expanded) },
                )
                Box(Modifier.weight(1f)) {
                    when {
                        listening && !expanded -> Orb(level, character, liveWords)
                        turns.isEmpty() && !listening && !thinking -> EmptyThread(level, character)
                        else -> Thread(turns, liveWords, listening, thinking, state, character, level)
                    }
                }
                Controls(listening, mic, onSend, onClose)
            }
        }
    }
}

/** The drag handle, title and the three actions: back, new chat, history. */
@Composable
private fun Header(
    title: String,
    expanded: Boolean,
    onClose: () -> Unit,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    onToggle: () -> Unit,
) {
    var drag by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(26.dp)
                .pointerInput(expanded) {
                    // Down opens the thread, up returns to the orb; the handle is the only
                    // drag area, so the launcher's own swipes keep their meaning elsewhere.
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (drag > 40f) onToggle() else if (drag < -40f) onToggle()
                            drag = 0f
                        },
                    ) { _, dy -> drag += dy }
                }
                .semantics { contentDescription = "Drag to expand or collapse" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f)))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.chat_close), tint = MasalaTheme.tokens.textMedium)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MasalaTheme.tokens.textHigh,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNewChat) { Icon(Icons.Rounded.Add, stringResource(R.string.chat_new), tint = MasalaTheme.tokens.textMedium) }
            IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, stringResource(R.string.chat_history), tint = MasalaTheme.tokens.textMedium) }
        }
    }
}

/** Talking: Sam and rings that follow the mic, with the words heard so far. */
@Composable
private fun Orb(level: () -> Float, character: Character, liveWords: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(contentAlignment = Alignment.Center) {
            val l = level().coerceIn(0f, 1f)
            val accent = MasalaTheme.tokens.accent
            androidx.compose.foundation.Canvas(Modifier.size(200.dp)) {
                val base = size.minDimension / 2f
                repeat(3) { i ->
                    val grow = 0.55f + 0.45f * l + i * 0.14f
                    drawCircle(
                        color = accent.copy(alpha = (0.20f - i * 0.05f) * (0.5f + l)),
                        radius = base * grow * 0.86f,
                        center = Offset(size.width / 2f, size.height / 2f),
                    )
                }
            }
            MasalaCharacter(character, CharacterState.LISTENING, Modifier.size(120.dp), level, description = stringResource(R.string.listening_sam))
        }
        Text(
            liveWords.ifBlank { stringResource(R.string.command_listening) },
            style = MaterialTheme.typography.titleMedium,
            color = if (liveWords.isBlank()) MasalaTheme.tokens.textMedium else MasalaTheme.tokens.textHigh,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
        )
        Text(
            stringResource(R.string.chat_drag_for_thread),
            style = MaterialTheme.typography.bodySmall,
            color = MasalaTheme.tokens.textLow,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun EmptyThread(level: () -> Float, character: Character) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        MasalaCharacter(character, CharacterState.HELLO, Modifier.size(110.dp), level, description = null)
        Text(
            stringResource(R.string.chat_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MasalaTheme.tokens.textMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp, start = 32.dp, end = 32.dp),
        )
    }
}

/** The conversation, oldest first, with the live turn and the working row at the bottom. */
@Composable
private fun Thread(
    turns: List<ChatTurn>,
    liveWords: String,
    listening: Boolean,
    thinking: Boolean,
    state: CommandState,
    character: Character,
    level: () -> Float,
) {
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, liveWords, thinking) {
        val last = list.layoutInfo.totalItemsCount - 1
        if (last >= 0) list.animateScrollToItem(last)
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(turns) { index, turn -> Bubble(turn, key = index) }
        if (listening) {
            item("live") {
                Bubble(
                    ChatTurn(TurnRole.USER, liveWords.ifBlank { stringResource(R.string.command_listening) }),
                    live = true,
                )
            }
        }
        if (thinking) {
            item("working") {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MasalaTheme.tokens.accent)
                    Text(
                        stringResource(R.string.chat_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MasalaTheme.tokens.textMedium,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
        item("tail") { Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun Bubble(turn: ChatTurn, live: Boolean = false, key: Int = -1) {
    val user = turn.role == TurnRole.USER
    val error = turn.role == TurnRole.ERROR
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (user) 18.dp else 6.dp,
        bottomEnd = if (user) 6.dp else 18.dp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(0.92f).clip(shape)
                .background(
                    when {
                        user -> MasalaTheme.tokens.accent.copy(alpha = if (live) 0.22f else 0.92f)
                        error -> Color(0x33FF6B6B)
                        else -> Color.White.copy(alpha = 0.06f)
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                turn.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (user && !live) MasalaTheme.tokens.onAccent else MasalaTheme.tokens.textHigh,
                fontSize = 14.sp,
            )
            if (turn.steps.isNotEmpty()) {
                Text(
                    turn.steps.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MasalaTheme.tokens.textLow,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Mic, and while listening the send/cancel pair (send = stop now, cancel = discard). */
@Composable
private fun Controls(listening: Boolean, mic: MicPress, onSend: () -> Unit, onClose: () -> Unit) {
    val tokens = MasalaTheme.tokens
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (listening) {
            IconButton(onClick = onClose, modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f))) {
                Icon(Icons.Rounded.Close, stringResource(R.string.listening_cancel), tint = tokens.textHigh)
            }
            Spacer(Modifier.width(20.dp))
            MicButton(mic, size = 68.dp)
            Spacer(Modifier.width(20.dp))
            IconButton(onClick = onSend, modifier = Modifier.size(52.dp).clip(CircleShape).background(tokens.accent)) {
                Icon(Icons.Rounded.Stop, stringResource(R.string.listening_stop), tint = tokens.onAccent)
            }
        } else {
            MicButton(mic, size = 68.dp)
        }
    }
}
