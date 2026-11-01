package com.samosaai.masala.ui.chat

import android.content.Intent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.samosaai.masala.R
import com.samosaai.masala.ui.components.Markdown
import com.samosaai.masala.agent.CommandState
import com.samosaai.masala.chat.ChatResult
import com.samosaai.masala.chat.ChatSession
import com.samosaai.masala.chat.ExpertCredit
import com.samosaai.masala.chat.ChatTurn
import com.samosaai.masala.chat.TurnRole
import com.samosaai.masala.data.Character
import com.samosaai.masala.masala
import com.samosaai.masala.miniapps.openMiniApp
import com.samosaai.masala.ui.character.CharacterState
import com.samosaai.masala.ui.character.MasalaCharacter
import com.samosaai.masala.ui.home.MicButton
import com.samosaai.masala.ui.settings.SettingsActivity
import com.samosaai.masala.ui.theme.MasalaColors
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
    mic: () -> Unit,
    serverProvider: Boolean = false,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAsk: (String) -> Unit = {},
    onClose: () -> Unit,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    onDeleteTurn: (Int) -> Unit = {},
    /** The installed experts, offered in an empty thread and from the header. */
    experts: List<ExpertCredit> = emptyList(),
    onAskExpert: (ExpertCredit) -> Unit = {},
    /** Asked after a missing access was granted, so the step can run again. */
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = MasalaTheme.tokens
    // A step that lacked access still gets its answer; this is how the user fixes it afterwards.
    val grant = com.samosaai.masala.ui.components.rememberCapabilityGrant { granted -> if (granted) onRetry() }
    val listening = state == CommandState.Listening
    val thinking = state is CommandState.Thinking
    val turns = session?.turns.orEmpty()
    // A newly stored turn pulls the thread up on its own; talking shows the orb. Keyed on the
    // last turn, not on size alone, so a collapse the user made stays collapsed.
    val lastTurn = turns.lastOrNull()
    LaunchedEffect(lastTurn) {
        if (!listening && lastTurn != null) onExpanded(true)
    }
    val threadHeight by animateFloatAsState(if (expanded) 0.88f else 0.52f, spring(stiffness = 380f), label = "sheet")

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Dims home so the conversation reads over any wallpaper, then the panel itself
        // is opaque: nothing of the launcher shows through a bubble.
        Box(
            Modifier.fillMaxSize().background(Color(0x8A000000))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { onClose() },
        )
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).fillMaxHeight(threadHeight)
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
                    onExpanded = onExpanded,
                    experts = experts,
                    onAskExpert = onAskExpert,
                )
                Box(Modifier.weight(1f)) {
                    when {
                        // Collapsed is the live orb, whether or not a conversation exists:
                        // that is the state's whole point, and what the swipe returns to.
                        !expanded -> Orb(
                            level = level,
                            character = character,
                            listening = listening,
                            showShimmer = listening && serverProvider && liveWords.isBlank(),
                            caption = liveWords.ifBlank {
                                (turns.lastOrNull { it.role == TurnRole.MASALA }?.text ?: "").take(140)
                            },
                        )
                        turns.isEmpty() && !listening && !thinking -> EmptyThread(level, character, experts, onAskExpert)
                        else -> Thread(
                            turns, liveWords, listening, thinking, state, character, level, onStop, onDeleteTurn,
                            onCollapse = { onExpanded(false) },
                            onGrant = grant,
                            onRetry = onRetry,
                        )
                    }
                }
                Controls(listening, mic, level, onSend, onAsk, onClose)
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
    onExpanded: (Boolean) -> Unit,
    experts: List<ExpertCredit> = emptyList(),
    onAskExpert: (ExpertCredit) -> Unit = {},
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
                            when {
                                // Down from the orb closes; down from the thread collapses;
                                // up from the orb opens. Up when already open does nothing.
                                drag > 40f && !expanded -> onClose()
                                drag > 40f && expanded -> onExpanded(false)
                                drag < -40f && !expanded -> onExpanded(true)
                            }
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
                Icon(Icons.Rounded.Close, stringResource(R.string.chat_close), tint = MasalaTheme.tokens.textHigh)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MasalaTheme.tokens.textHigh,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (experts.isNotEmpty()) {
                var asking by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { asking = true }) {
                        Icon(Icons.Rounded.Person, stringResource(R.string.chat_ask_someone), tint = MasalaTheme.tokens.textMedium)
                    }
                    DropdownMenu(expanded = asking, onDismissRequest = { asking = false }) {
                        experts.forEach { expert ->
                            DropdownMenuItem(
                                text = { Text(expert.title) },
                                onClick = {
                                    asking = false
                                    onAskExpert(expert)
                                },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onNewChat) { Icon(Icons.Rounded.Add, stringResource(R.string.chat_new), tint = MasalaTheme.tokens.textMedium) }
            IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, stringResource(R.string.chat_history), tint = MasalaTheme.tokens.textMedium) }
        }
    }
}

/** Talking: Sam and rings that follow the mic, with the words heard so far. */
@Composable
private fun Orb(level: () -> Float, character: Character, listening: Boolean, caption: String, showShimmer: Boolean = false) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(contentAlignment = Alignment.Center) {
            // The engine reports one level per 200 ms audio window; animating between them
            // turns the steps into a continuous breath instead of a jump every window.
            val raw = level().coerceIn(0f, 1f)
            val l by animateFloatAsState(
                raw,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                label = "orb-level",
            )
            val accent = MasalaTheme.tokens.accent
            // Sized to fit the collapsed sheet: canvas, character, caption and hint share
            // about 200 dp here, and a taller orb overflows center-first into the mic row.
            androidx.compose.foundation.Canvas(Modifier.size(150.dp)) {
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
            MasalaCharacter(
                character,
                if (listening) CharacterState.LISTENING else CharacterState.HELLO,
                Modifier.size(96.dp), { l },
                description = if (listening) stringResource(R.string.listening_sam) else null,
            )
        }
        if (showShimmer) {
            // The server sends no partials, so the orb would sit on a dead line until
            // the transcript lands. Pulse it instead; the animated value is read only
            // inside the draw lambda, so the pulse redraws without recomposing.
            val glow by rememberInfiniteTransition(label = "listening-shimmer")
                .animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "listening-alpha")
            Text(
                stringResource(R.string.command_listening),
                style = MaterialTheme.typography.titleMedium,
                color = MasalaTheme.tokens.textMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = glow }.padding(top = 12.dp, start = 24.dp, end = 24.dp),
            )
        } else {
            Text(
                caption.ifBlank { if (listening) stringResource(R.string.command_listening) else stringResource(R.string.chat_tap_mic) },
                style = MaterialTheme.typography.titleMedium,
                color = if (caption.isBlank()) MasalaTheme.tokens.textMedium else MasalaTheme.tokens.textHigh,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
            )
        }
        if (!listening) {
            Text(
                stringResource(R.string.chat_drag_for_thread),
                style = MaterialTheme.typography.bodySmall,
                color = MasalaTheme.tokens.textLow,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun EmptyThread(
    level: () -> Float,
    character: Character,
    experts: List<ExpertCredit>,
    onAskExpert: (ExpertCredit) -> Unit,
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        MasalaCharacter(character, CharacterState.HELLO, Modifier.size(110.dp), level, description = null)
        Text(
            stringResource(R.string.chat_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MasalaTheme.tokens.textMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp, start = 32.dp, end = 32.dp),
        )
        if (experts.isNotEmpty()) {
            // What Masala can do, shown rather than explained: tapping one starts the request.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                experts.forEach { expert ->
                    AssistChip(onClick = { onAskExpert(expert) }, label = { Text(expert.title) })
                }
            }
        }
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
    onStop: () -> Unit,
    onDeleteTurn: (Int) -> Unit,
    onCollapse: () -> Unit,
    onGrant: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, liveWords, thinking, (state as? CommandState.Thinking)?.steps?.size) {
        // A finished answer scrolls to its start, so the expert badge that credits it is visible;
        // while working, the steps at the bottom are what matters.
        val target = when {
            thinking || listening -> list.layoutInfo.totalItemsCount - 1
            turns.isNotEmpty() -> turns.lastIndex
            else -> -1
        }
        if (target >= 0) list.animateScrollToItem(target)
    }
    // The handle's rule, on the list itself: a downward pull past the top,
    // beyond 120 dp, returns to the orb. Down is the handle's collapse
    // direction (drag > 40 from the thread collapses); up from the thread
    // does nothing there, so upward overscroll only resets the count.
    // A fling is its own gesture, not a pull: it resets instead of adding up.
    val collapsePx = with(LocalDensity.current) { 120.dp.toPx() }
    var overscroll by remember { mutableFloatStateOf(0f) }
    val nested = remember(onCollapse, collapsePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Fling && available.y < 0f) overscroll = 0f
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Fling) {
                    overscroll = 0f
                    return Offset.Zero
                }
                if (available.y > 0f) {
                    overscroll += available.y
                    if (overscroll > collapsePx) {
                        overscroll = 0f
                        onCollapse()
                    }
                } else if (available.y < 0f) {
                    overscroll = 0f
                }
                return Offset.Zero
            }
        }
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp).nestedScroll(nested),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(turns) { index, turn -> Bubble(turn, key = index, onDelete = { onDeleteTurn(index) }) }
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
                StepTimeline(steps = (state as? CommandState.Thinking)?.steps.orEmpty(), onStop = onStop)
            }
        }
        val answered = state as? CommandState.Reply
        if (answered != null && answered.capabilities.isNotEmpty()) {
            item("access") { AccessRow(answered.capabilities.first(), onGrant, onRetry) }
        }
        item("tail") { Spacer(Modifier.height(6.dp)) }
    }
}

/** What the turn made: open it, or put it on the home screen. */
@Composable
private fun ResultCard(result: ChatResult) {
    val context = LocalContext.current
    val app = context.masala
    val scope = rememberCoroutineScope()
    // Created apps are pinned by the tool, so the card must not offer it again.
    var pinned by remember(result.ref) { mutableStateOf(app.layouts.isMiniAppPinned(result.ref)) }
    val miniApp = result.kind == "mini_app"
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            result.title.ifBlank { result.ref },
            style = MaterialTheme.typography.titleSmall,
            color = MasalaTheme.tokens.textHigh,
            maxLines = 1,
        )
        // Only a mini app or an installed app has an address we can open from here; a produced
        // file still gets its title, without a row of buttons that would do nothing.
        val canOpen = miniApp || result.kind == "installed_app"
        if (canOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    if (miniApp) context.openMiniApp(result.ref)
                    else runCatching { context.packageManager.getLaunchIntentForPackage(result.ref)?.let(context::startActivity) }
                }) { Text(stringResource(R.string.miniapp_open)) }
                if (miniApp) {
                    TextButton(onClick = {
                        scope.launch {
                            app.layouts.pinMiniApp(result.ref, result.title)
                            pinned = true
                        }
                    }) {
                        Text(stringResource(if (pinned) R.string.miniapp_pinned else R.string.miniapp_pin))
                    }
                }
            }
        }
    }
}

/** The answer came without a step that needed access; offer the grant, then the retry. */
@Composable
private fun AccessRow(capability: String, onGrant: (String) -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Text(
            stringResource(R.string.chat_needs_access),
            style = MaterialTheme.typography.bodySmall,
            color = MasalaTheme.tokens.textMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onGrant(capability) }) { Text(stringResource(R.string.command_grant)) }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.command_try_again)) }
        }
    }
}

/** Beyond this the badges would wrap under the text; the steps above still name every expert. */
private const val MAX_EXPERT_BADGES = 3

@Composable
private fun Bubble(turn: ChatTurn, live: Boolean = false, key: Int = -1, onDelete: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    // Live turns are transient; only stored turns can be deleted.
    val longPress = if (onDelete != null && !live) {
        Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })
    } else {
        Modifier
    }
    fun dismissDelete() {
        menu = false
        onDelete?.invoke()
    }
    val user = turn.role == TurnRole.USER
    if (turn.role == TurnRole.SYSTEM) {
        // A history summary: the app wrote it, so it reads as a note, not as a reply.
        Box(Modifier.fillMaxWidth()) {
            Column(
                longPress.fillMaxWidth().padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
            Text(
                stringResource(R.string.chat_summary_note),
                style = MaterialTheme.typography.labelSmall,
                color = MasalaTheme.tokens.textLow,
            )
            Text(
                turn.text,
                style = MaterialTheme.typography.bodySmall,
                color = MasalaTheme.tokens.textMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            }
            TurnMenu(menu, onDismiss = { menu = false }, onDelete = ::dismissDelete)
        }
        return
    }
    val error = turn.role == TurnRole.ERROR
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (user) 18.dp else 6.dp,
        bottomEnd = if (user) 6.dp else 18.dp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Box(Modifier.fillMaxWidth(0.92f)) {
            TurnMenu(menu, onDismiss = { menu = false }, onDelete = ::dismissDelete)
        Column(
            longPress.fillMaxWidth().clip(shape)
                .background(
                    when {
                        user -> MasalaTheme.tokens.accent.copy(alpha = if (live) 0.22f else 0.92f)
                        error -> Color(0x33FF6B6B)
                        else -> Color.White.copy(alpha = 0.06f)
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (turn.experts.isNotEmpty() && !user) {
                // Who answered: the expert's name above the text, so scrolling back stays clear.
                Row(
                    Modifier.padding(bottom = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val context = LocalContext.current
                    turn.experts.take(MAX_EXPERT_BADGES).forEach { expert ->
                        Text(
                            expert.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MasalaColors.Gold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MasalaColors.Gold.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                // Who answered, and where its instructions can be changed.
                                .clickable {
                                    context.startActivity(
                                        Intent(context, SettingsActivity::class.java)
                                            .putExtra(SettingsActivity.EXTRA_PAGE, "PERSONAS"),
                                    )
                                },
                        )
                    }
                }
            }
            // The model answers in Markdown — tables, lists, code — and the bubble is where it
            // lands, so render it, not the raw pipes and hashes. The user's own words stay plain
            // text on the accent tile.
            if (user) {
                Text(
                    turn.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (!live) MasalaTheme.tokens.onAccent else MasalaTheme.tokens.textHigh,
                    fontSize = 14.sp,
                )
            } else {
                Markdown(turn.text)
            }
            turn.results.forEach { result -> ResultCard(result) }
            if (turn.steps.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    turn.steps.forEach { step ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(MasalaColors.Cardamom.copy(alpha = 0.85f)))
                            Text(
                                step,
                                style = MaterialTheme.typography.labelSmall,
                                color = MasalaTheme.tokens.textLow,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/** Long-press menu on one stored turn: deleting it also drops it from the agent's history. */
@Composable
private fun TurnMenu(expanded: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.chat_delete_turn)) }, onClick = onDelete)
    }
}

/**
 * Mic to open Sam, and while listening the cancel/send pair. Sending is explicit: the
 * arrow ends the take now. There is no mic button while listening — tapping it is how
 * a take starts, so showing one there could only end the take by accident.
 * Otherwise a multi-line type field sits beside the mic, so a failed microphone never
 * strands the conversation; the send arrow appears once there is text.
 */
@Composable
private fun Controls(
    listening: Boolean,
    mic: () -> Unit,
    level: () -> Float,
    onSend: () -> Unit,
    onAsk: (String) -> Unit = {},
    onClose: () -> Unit,
) {
    val tokens = MasalaTheme.tokens
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
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
                IconButton(onClick = onSend, modifier = Modifier.size(68.dp).clip(CircleShape).background(tokens.accent)) {
                    Icon(Icons.Rounded.Send, stringResource(R.string.chat_send_now), tint = tokens.onAccent)
                }
            } else {
                var text by remember { mutableStateOf("") }
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.command_hint)) },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (text.isBlank()) {
                    MicButton(mic, level = level, size = 52.dp)
                } else {
                    IconButton(
                        onClick = { onAsk(text.trim()); text = "" },
                        modifier = Modifier.size(52.dp).clip(CircleShape).background(tokens.accent),
                    ) {
                        Icon(Icons.Rounded.Send, stringResource(R.string.chat_send_now), tint = tokens.onAccent)
                    }
                }
            }
        }
    }
}
