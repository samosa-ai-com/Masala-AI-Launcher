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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    level: () -> Float,
    state: CommandState,
    character: Character,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    mic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAsk: (String, List<String>) -> Unit = { _, _ -> },
    onClose: () -> Unit,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    /** This chat's "Allow all": skip confirmations for anything that isn't destructive. */
    allowAll: Boolean = false,
    onAllowAll: (Boolean) -> Unit = {},
    onRevertTurn: (Int) -> Unit = {},
    onContinue: () -> Unit = {},
    /** ms since the live take started; the timer and near-cap warning read this. */
    elapsedMs: Long = 0L,
    /** False until the take is really capturing; the UI holds "Get ready…" until then. */
    ready: Boolean = true,
    /** Text dropped back into the type field by a revert, so it can be edited and resent. */
    prefill: String = "",
    /** Bumped every revert so an identical text still replaces what is typed. */
    prefillNonce: Int = 0,
    /** The box calls this once it has taken the prefill, so it can't refill later. */
    onPrefillConsumed: () -> Unit = {},
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
    val paused = state == CommandState.ListeningPaused
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
            // Everything rides above the keyboard: the thread and the type field move up
            // together, so no message is ever stuck behind it. (The activity uses
            // adjustNothing, so without this the window never makes room.) The padding comes
            // before the height, so the sheet's share is of the space above the keyboard, not
            // of the screen with the keyboard taken out of it (that left only the header).
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).imePadding().fillMaxHeight(threadHeight)
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
                    allowAll = allowAll,
                    onAllowAll = onAllowAll,
                    onToggle = { onExpanded(!expanded) },
                    onExpanded = onExpanded,
                    experts = experts,
                    onAskExpert = onAskExpert,
                )
                Box(Modifier.weight(1f)) {
                    when {
                        // Collapsed is the live orb, whether or not a conversation exists:
                        // that is the state's whole point, and what the swipe returns to.
                        // A cap-paused take keeps the orb (no shimmer): its words are kept.
                        !expanded -> Orb(
                            level = level,
                            character = character,
                            listening = listening || paused,
                            // Words are not streamed while talking: the pulse stands in until
                            // the final transcript is sent.
                            showShimmer = listening,
                            caption = if (paused) stringResource(R.string.voice_paused) else (turns.lastOrNull { it.role == TurnRole.MASALA }?.text ?: "").take(140),
                        )
                        turns.isEmpty() && !listening && !paused && !thinking -> EmptyThread(level, character, experts, onAskExpert)
                        else -> Thread(
                            turns, listening, paused, ready, elapsedMs, thinking, state, character, level, onStop,
                            onRevertTurn,
                            onCollapse = { onExpanded(false) },
                            onGrant = grant,
                            onRetry = onRetry,
                        )
                    }
                }
                Controls(listening, paused, ready, elapsedMs, mic, level, onSend, onContinue, onAsk, onClose, prefill, prefillNonce, onPrefillConsumed)
            }
        }
    }
}

/** The drag handle, title, the "Allow all" switch and the three actions: back, new chat, history. */
@Composable
private fun Header(
    title: String,
    expanded: Boolean,
    onClose: () -> Unit,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    allowAll: Boolean,
    onAllowAll: (Boolean) -> Unit,
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
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            )
            // One icon, so the title keeps the room; gold while on, and the strip below says so.
            val context = androidx.compose.ui.platform.LocalContext.current
            val allowLabel = stringResource(R.string.chat_allow_all)
            val allowState = stringResource(if (allowAll) R.string.chat_allow_all_state_on else R.string.chat_allow_all_state_off)
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (allowAll) MasalaTheme.tokens.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .combinedClickable(
                        onClick = { onAllowAll(!allowAll) },
                        onLongClick = { android.widget.Toast.makeText(context, allowLabel, android.widget.Toast.LENGTH_SHORT).show() },
                    )
                    .semantics {
                        contentDescription = allowLabel
                        stateDescription = allowState
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Bolt,
                    null,
                    tint = if (allowAll) MasalaTheme.tokens.accent else MasalaTheme.tokens.textLow,
                )
            }
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
        // While confirmations are skipped it says so, and turning it off is one tap away.
        if (allowAll) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MasalaTheme.tokens.accent.copy(alpha = 0.12f))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Bolt, null, tint = MasalaTheme.tokens.accent, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.chat_allow_all_on),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasalaTheme.tokens.textHigh,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = { onAllowAll(false) }) { Text(stringResource(R.string.chat_allow_all_turn_off)) }
            }
        }
    }
}

/** Talking: Sam and rings that follow the mic, with the last answer when idle. */
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
            // No words show while talking, so the orb would sit on a dead line until the
            // transcript lands. Pulse it instead; the animated value is read only inside
            // the draw lambda, so the pulse redraws without recomposing.
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
    listening: Boolean,
    paused: Boolean,
    ready: Boolean,
    elapsedMs: Long,
    thinking: Boolean,
    state: CommandState,
    character: Character,
    level: () -> Float,
    onStop: () -> Unit,
    onRevertTurn: (Int) -> Unit,
    onCollapse: () -> Unit,
    onGrant: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val list = rememberLazyListState()
    // A blank-prompt Thinking is the gap between Send and the final transcript.
    val transcribing = (state as? CommandState.Thinking)?.prompt?.isEmpty() == true
    LaunchedEffect(turns.size, transcribing, thinking, (state as? CommandState.Thinking)?.steps?.size) {
        // A finished answer scrolls to its start, so the expert badge that credits it is visible;
        // while working, the steps at the bottom are what matters.
        val target = when {
            thinking || listening || paused -> list.layoutInfo.totalItemsCount - 1
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
        itemsIndexed(turns) { index, turn ->
            // Only the user's own messages carry a menu: reverting means
            // re-sending the prompt, and there is nothing to redo from a reply.
            Bubble(
                turn,
                key = index,
                onRevert = if (turn.role == TurnRole.USER) {
                    { onRevertTurn(index) }
                } else {
                    null
                },
            )
        }
        if (listening || paused || transcribing) {
            item("live") {
                val base = stringResource(
                    when {
                        paused -> R.string.voice_paused
                        listening && !ready -> R.string.voice_get_ready
                        listening -> R.string.command_listening
                        else -> R.string.command_transcribing
                    },
                )
                Bubble(
                    ChatTurn(
                        TurnRole.USER,
                        if ((listening || paused) && elapsedMs > 0) "$base · ${elapsedLabel(elapsedMs)}" else base,
                    ),
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
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            result.title.ifBlank { result.ref },
            style = MaterialTheme.typography.titleSmall,
            color = MasalaTheme.tokens.textHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Only a mini app or an installed app has an address we can open from here; a produced
        // file still gets its title, without a row of buttons that would do nothing.
        // A generated wallpaper gets its preview with Set / Save actions instead.
        if (result.kind == "wallpaper") {
            WallpaperPreview(result, app)
            return@Column
        }
        if (result.kind == "image" || result.kind == "segmentation") {
            GeneratedPreview(result, app)
            return@Column
        }
        if (result.kind == "audio") {
            AudioPreview(result)
            return@Column
        }
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

/** A generated wallpaper: sampled preview with Set (home/lock/both) and gallery Save. */
@Composable
private fun WallpaperPreview(result: ChatResult, app: com.samosaai.masala.MasalaApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(result.ref) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    // Decode off the main thread: a full-size file decodes megabytes of pixels.
    LaunchedEffect(result.ref) {
        bitmap = withContext(Dispatchers.IO) { decodePreview(result.ref) }
    }
    fun run(action: suspend () -> com.samosaai.masala.core.tool.ActionResult) {
        scope.launch {
            val r = runCatching { action() }.getOrElse { com.samosaai.masala.core.tool.ActionResult.error(it.message ?: "?") }
            android.widget.Toast.makeText(context, r.message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    bitmap?.let {
        androidx.compose.foundation.Image(
            it, result.title.ifBlank { null },
            modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
    // Two rows of two: four buttons side by side clip on 360-390dp screens.
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { run { app.wallpaperTool.setWallpaper(result.ref, "home") } }) { Text(stringResource(R.string.wallpaper_set_home)) }
        TextButton(onClick = { run { app.wallpaperTool.setWallpaper(result.ref, "lock") } }) { Text(stringResource(R.string.wallpaper_set_lock)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { run { app.wallpaperTool.setWallpaper(result.ref, "both") } }) { Text(stringResource(R.string.wallpaper_set_both)) }
        TextButton(onClick = { run { app.wallpaperTool.saveToGallery(result.ref) } }) { Text(stringResource(R.string.result_save)) }
    }
}

/** A generated/segmented image: sampled preview with gallery Save. */
@Composable
private fun GeneratedPreview(result: ChatResult, app: com.samosaai.masala.MasalaApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(result.ref) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(result.ref) {
        bitmap = withContext(Dispatchers.IO) { decodePreview(result.ref) }
    }
    bitmap?.let {
        androidx.compose.foundation.Image(
            it, result.title.ifBlank { null },
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = {
            scope.launch {
                val r = runCatching { app.wallpaperTool.saveToGallery(result.ref) }
                    .getOrElse { com.samosaai.masala.core.tool.ActionResult.error(it.message ?: "?") }
                android.widget.Toast.makeText(context, r.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }) { Text(stringResource(R.string.result_save)) }
    }
}

/** A synthesized clip: plays the saved file. */
@Composable
private fun AudioPreview(result: ChatResult) {
    val context = LocalContext.current
    var playing by remember(result.ref) { mutableStateOf(false) }
    var player by remember(result.ref) { mutableStateOf<android.media.MediaPlayer?>(null) }
    androidx.compose.runtime.DisposableEffect(result.ref) {
        onDispose { runCatching { player?.release() }; player = null }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = {
            runCatching {
                if (playing) {
                    player?.stop(); runCatching { player?.release() }; player = null; playing = false
                } else {
                    val mp = android.media.MediaPlayer().apply {
                        setDataSource(result.ref)
                        prepare()
                        start()
                        setOnCompletionListener { playing = false }
                    }
                    player = mp; playing = true
                }
            }.onFailure {
                android.widget.Toast.makeText(context, it.message ?: "?", android.widget.Toast.LENGTH_SHORT).show()
            }
        }) { Text(if (playing) stringResource(R.string.media_stop) else stringResource(R.string.media_play)) }
    }
}

/** Preview-sized bitmap for a wallpaper file; null when the file isn't a readable image. */
private fun decodePreview(path: String): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 720 || bounds.outHeight / (sample * 2) >= 1280) sample *= 2
    android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}.getOrNull()

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
private fun Bubble(
    turn: ChatTurn,
    live: Boolean = false,
    key: Int = -1,
    onRevert: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    // Live turns are transient; only stored user turns can be reverted to.
    val longPress = if (onRevert != null && !live) {
        Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })
    } else {
        Modifier
    }
    fun dismissRevert() {
        menu = false
        onRevert?.invoke()
    }
    val user = turn.role == TurnRole.USER
    if (turn.role == TurnRole.SYSTEM) {
        // A history summary: the app wrote it, so it reads as a note, not as a reply.
        // No menu: only the user's own messages can be reverted to.
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
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
            if (onRevert != null) {
                TurnMenu(menu, onDismiss = { menu = false }, onRevert = ::dismissRevert)
            }
        Column(
            longPress.fillMaxWidth().clip(shape)
                .background(
                    when {
                        user -> MasalaTheme.tokens.accent.copy(alpha = if (live) 0.6f else 0.92f)
                        error -> MaterialTheme.colorScheme.errorContainer
                        // Opaque theme pair: the old translucent white washed out over wallpapers.
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            val bubbleContent = MaterialTheme.colorScheme.onSurface
            CompositionLocalProvider(
                LocalContentColor provides
                    if (error) MaterialTheme.colorScheme.onErrorContainer else bubbleContent,
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
}

/** Long-press menu on one of the user's turns: reverting also updates the agent's history. */
@Composable
private fun TurnMenu(expanded: Boolean, onDismiss: () -> Unit, onRevert: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.chat_revert_to_here)) }, onClick = onRevert)
    }
}

/** "m:ss" for the listening timer. */
private fun elapsedLabel(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * Mic to open Sam, and while listening the cancel/send pair. Sending is explicit: the
 * arrow ends the take now. There is no mic button while listening — tapping it is how
 * a take starts, so showing one there could only end the take by accident.
 * Otherwise a multi-line type field sits beside the mic, so a failed microphone never
 * strands the conversation; the send arrow appears once there is text.
 *
 * A paused take (5-minute cap) offers Continue / Send / Cancel instead: its audio and
 * transcript are kept either way.
 */
@Composable
private fun Controls(
    listening: Boolean,
    paused: Boolean,
    ready: Boolean,
    elapsedMs: Long,
    mic: () -> Unit,
    level: () -> Float,
    onSend: () -> Unit,
    onContinue: () -> Unit = {},
    onAsk: (String, List<String>) -> Unit = { _, _ -> },
    onClose: () -> Unit,
    prefill: String = "",
    prefillNonce: Int = 0,
    onPrefillConsumed: () -> Unit = {},
) {
    val tokens = MasalaTheme.tokens
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tooManyText = stringResource(R.string.chat_too_many_images, com.samosaai.masala.chat.MAX_IMAGES_PER_MESSAGE)
    val badImageText = stringResource(R.string.chat_bad_image)
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        if (listening && elapsedMs >= com.samosaai.masala.audio.SttEngine.CAP_WARN_MS) {
            Text(
                stringResource(R.string.voice_cap_warning),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (listening) {
                IconButton(onClick = onClose, modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f))) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.listening_cancel), tint = tokens.textHigh)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    (if (ready) stringResource(R.string.command_listening) else stringResource(R.string.voice_get_ready)) +
                        if (elapsedMs > 0) " · ${elapsedLabel(elapsedMs)}" else "",
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textMedium,
                )
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = onSend,
                    enabled = ready,
                    modifier = Modifier.size(68.dp).clip(CircleShape).background(tokens.accent.copy(alpha = if (ready) 1f else 0.4f)),
                ) {
                    Icon(Icons.Rounded.Send, stringResource(R.string.chat_send_now), tint = tokens.onAccent.copy(alpha = if (ready) 1f else 0.6f))
                }
            } else if (paused) {
                IconButton(onClick = onClose, modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f))) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.listening_cancel), tint = tokens.textHigh)
                }
                Spacer(Modifier.width(12.dp))
                androidx.compose.material3.Button(onClick = onContinue) {
                    Text(stringResource(R.string.voice_continue))
                }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = onSend, modifier = Modifier.size(52.dp).clip(CircleShape).background(tokens.accent)) {
                    Icon(Icons.Rounded.Send, stringResource(R.string.chat_send_now), tint = tokens.onAccent)
                }
            } else {
                var text by remember { mutableStateOf("") }
                var queued by remember { mutableStateOf(listOf<com.samosaai.masala.chat.AttachedImage>()) }
                var cameraTarget by remember { mutableStateOf<android.net.Uri?>(null) }
                // A revert drops the message back here for editing; the nonce re-applies
                // even when the same words are reverted twice in a row. Consuming it
                // at once stops the old text refilling the box on a later rebuild
                // (mic, drawer, reopen) or leaking into another chat.
                LaunchedEffect(prefillNonce) {
                    if (prefillNonce > 0) {
                        text = prefill
                        onPrefillConsumed()
                    }
                }
                fun addUri(uri: android.net.Uri, name: String) {
                    if (queued.size >= com.samosaai.masala.chat.MAX_IMAGES_PER_MESSAGE) {
                        android.widget.Toast.makeText(context, tooManyText, android.widget.Toast.LENGTH_SHORT).show()
                        return
                    }
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val b64 = com.samosaai.masala.chat.compressToJpegBase64(context, uri)
                        if (b64 == null) {
                            launch(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(context, badImageText, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            val item = com.samosaai.masala.chat.AttachedImage(
                                id = "${System.currentTimeMillis()}-${queued.size}", name = name, jpegBase64 = b64,
                            )
                            launch(kotlinx.coroutines.Dispatchers.Main) { queued = queued + item }
                        }
                    }
                }
                val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(maxItems = com.samosaai.masala.chat.MAX_IMAGES_PER_MESSAGE),
                ) { uris -> uris.forEachIndexed { i, u -> addUri(u, "image ${queued.size + i + 1}") } }
                val takePhoto = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
                ) { ok -> if (ok) cameraTarget?.let { addUri(it, "photo") } }
                Column(Modifier.weight(1f)) {
                com.samosaai.masala.chat.AttachmentStrip(queued, onRemove = { id -> queued = queued.filterNot { it.id == id } })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(
                        onClick = {
                            if (queued.size >= com.samosaai.masala.chat.MAX_IMAGES_PER_MESSAGE) {
                                android.widget.Toast.makeText(context, tooManyText, android.widget.Toast.LENGTH_SHORT).show()
                            } else pickImage.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.chat_attach_image), tint = tokens.textMedium)
                    }
                    IconButton(
                        onClick = {
                            if (queued.size >= com.samosaai.masala.chat.MAX_IMAGES_PER_MESSAGE) {
                                android.widget.Toast.makeText(context, tooManyText, android.widget.Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            val dir = java.io.File(context.filesDir, "work").apply { mkdirs() }
                            val file = com.samosaai.masala.tools.PhotoTool.photoFile(dir)
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            cameraTarget = uri
                            takePhoto.launch(uri)
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Rounded.PhotoCamera, stringResource(R.string.chat_take_photo), tint = tokens.textMedium)
                    }
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
                    if (text.isBlank() && queued.isEmpty()) {
                        MicButton(mic, level = level, size = 52.dp)
                    } else {
                        IconButton(
                            onClick = {
                                onAsk(text.trim(), queued.map { it.jpegBase64 })
                                text = ""
                                queued = emptyList()
                            },
                            modifier = Modifier.size(52.dp).clip(CircleShape).background(tokens.accent),
                        ) {
                            Icon(Icons.Rounded.Send, stringResource(R.string.chat_send_now), tint = tokens.onAccent)
                        }
                    }
                }
                }
            }
        }
    }
}
