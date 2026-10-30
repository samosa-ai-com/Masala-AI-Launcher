package com.samosaai.masala.ui.home

import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.components.Radii
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.R
import com.samosaai.masala.agent.CommandState
import com.samosaai.masala.agent.SkillChip
import com.samosaai.masala.ui.components.Markdown
import com.samosaai.masala.ui.components.rememberCapabilityGrant
import com.samosaai.masala.ui.skills.SkillSheetActivity
import com.samosaai.masala.ui.secondbrain.ProposalCards
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.samosaai.masala.core.ai.MicMode

/** What the mic button does when pressed and released, with the microphone permission asked on the first press. */
class MicPress(val onPress: () -> Unit, val onRelease: () -> Unit)

/** A press longer than this is hold-to-talk; shorter is a tap. */
private const val HOLD_AFTER_MS = 300L

/**
 * The mic (plan UI refresh U2, decision 6). In tap mode a tap listens until the speaker pauses (a second
 * tap sends early) and a press held past [HOLD_AFTER_MS] is walkie-talkie; in hold mode only holding
 * records.
 */
@Composable
fun rememberMicPress(app: MasalaApp): MicPress {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val granted = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* the next press listens */ }
    return remember(app) {
        var holdStart: Job? = null
        var holding = false
        var permitted = false
        MicPress(
            onPress = {
                permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!permitted) {
                    granted.launch(Manifest.permission.RECORD_AUDIO)
                } else if (app.aiSettings.profile.value.micMode == MicMode.HOLD) {
                    holding = app.commands.startListening()
                } else if (app.commands.state.value != CommandState.Listening) {
                    holding = false
                    holdStart = scope.launch {
                        delay(HOLD_AFTER_MS)
                        holding = app.commands.startListening()
                    }
                }
            },
            onRelease = {
                if (permitted) {
                    holdStart?.cancel()
                    holdStart = null
                    when {
                        holding -> app.commands.stopListening()
                        app.aiSettings.profile.value.micMode == MicMode.TAP -> app.commands.tapToTalk()
                    }
                    holding = false
                }
            },
        )
    }
}

/**
 * "Search or ask Masala" with the mic (UI refresh U3, home boards): one glass pill above the dock in
 * place of the two floating buttons. Tapping the pill opens search; the mic talks.
 */
@Composable
fun SearchPill(onSearch: () -> Unit, mic: MicPress, modifier: Modifier = Modifier) {
    val tokens = MasalaTheme.tokens
    val shape = RoundedCornerShape(50)
    val label = stringResource(R.string.home_search_or_ask)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(58.dp).clip(shape)
            .background(tokens.wallpaperGlass).border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onSearch)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = tokens.onWallpaperMedium, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tokens.onWallpaperMedium, modifier = Modifier.weight(1f))
        MicButton(mic, size = 46.dp)
    }
}

/** Opens the reply card for typing, without running anything yet. */
@Composable
fun AskButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.command_open_chat)
    Surface(
        onClick = onOpen,
        modifier.semantics { contentDescription = label },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ChatBubble, contentDescription = null) }
    }
}

/** The mic: tap to talk, or hold (see [rememberMicPress]). */
@Composable
fun MicButton(press: MicPress, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val label = stringResource(R.string.command_mic)
    Surface(
        modifier
            .size(size)
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(press) {
                detectTapGestures(onPress = {
                    press.onPress()
                    tryAwaitRelease()
                    press.onRelease()
                })
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Mic, contentDescription = null) }
    }
}

/**
 * The reply card over home or the assist screen (plan Phase 4 M3): what Masala is doing, the reply as
 * Markdown, a skill chip if the request fits a persona, a follow-up field with push-to-talk, and
 * Read aloud. Reads the app's [com.samosaai.masala.agent.CommandController], so a command started on
 * home keeps going if the card is left.
 */
@Composable
fun ReplyCard(
    app: MasalaApp,
    readAloud: Boolean,
    modifier: Modifier = Modifier,
    /** Home shows listening and work in their own overlay and sheet (UI refresh U4); the card skips them. */
    sheets: Boolean = false,
    character: com.samosaai.masala.data.Character? = null,
) {
    val state by app.commands.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val press = rememberMicPress(app)
    var text by remember { mutableStateOf("") }
    val retryPrompt = stringResource(R.string.capability_retry_prompt)
    // A dialog that was allowed carries on by itself; a settings screen needs Try again.
    val grant = rememberCapabilityGrant { granted -> if (granted) app.commands.ask(retryPrompt) }

    val reply = state as? CommandState.Reply
    // "Read replies aloud" applies to every reply; the button reads one on request.
    LaunchedEffect(reply) { if (readAloud && reply != null && app.commands.claimReadAloud(reply)) app.speakReply(reply.text) }
    if (state == CommandState.Idle) return
    if (sheets && (state == CommandState.Listening || state is CommandState.Thinking)) return

    // A glass sheet over home (UI refresh U3); opened for typing, its field takes focus.
    val field = remember { FocusRequester() }
    LaunchedEffect(state) { if (state == CommandState.Input) runCatching { field.requestFocus() } }
    Card(
        modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(Radii.sheet),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f), contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, MasalaTheme.tokens.hairline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Sam in the card's corner for a reply or a failure (beside the content, never on it).
            if (character != null && (state is CommandState.Reply || state is CommandState.Error)) {
                val speaking by app.speaking.collectAsStateWithLifecycle()
                com.samosaai.masala.ui.character.MasalaCharacter(
                    character,
                    com.samosaai.masala.ui.character.characterStateFor(state, speaking, night = false),
                    Modifier.size(40.dp),
                )
            }
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val s = state) {
                    CommandState.Listening -> Text(stringResource(R.string.command_listening), style = MaterialTheme.typography.titleMedium)
                    is CommandState.Thinking -> {
                        if (s.prompt.isNotBlank()) Text(s.prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.command_thinking), style = MaterialTheme.typography.titleMedium)
                        Steps(s.steps)
                    }
                    is CommandState.Reply -> {
                        if (s.prompt.isNotBlank()) Text(s.prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Steps(s.steps)
                        Markdown(s.text)
                        s.skill?.let { chip -> SkillChipButton(chip) { openSkill(context, app, chip, s.prompt) } }
                        ProposalCards(app)
                    }
                    is CommandState.Error -> {
                        Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                        s.capability?.let { marker ->
                            Row {
                                TextButton(onClick = { grant(marker) }) { Text(stringResource(R.string.command_grant)) }
                                TextButton(onClick = { app.commands.ask(retryPrompt) }) { Text(stringResource(R.string.command_try_again)) }
                            }
                        }
                    }
                    CommandState.Input, CommandState.Idle -> Unit
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text,
                    { text = it },
                    Modifier.weight(1f).focusRequester(field),
                    placeholder = { Text(stringResource(R.string.command_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { app.commands.ask(text); text = "" } }),
                )
                if (text.isNotBlank()) {
                    TextButton(onClick = { app.commands.ask(text); text = "" }) { Text(stringResource(R.string.skill_send)) }
                } else {
                    MicButton(press)
                }
            }
            Row {
                if (reply != null) TextButton(onClick = {
                    scope.launch {
                        app.speakReply(reply.text)?.let { reason ->
                            android.widget.Toast.makeText(context, reason, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(stringResource(R.string.command_read_aloud)) }
                Box(Modifier.weight(1f))
                TextButton(onClick = { app.stopSpeaking(); app.commands.dismiss() }) { Text(stringResource(R.string.command_close)) }
            }
        }
    }
}

@Composable
private fun Steps(steps: List<String>) {
    steps.forEach { Text(stringResource(R.string.skill_step_done, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun SkillChipButton(chip: SkillChip, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(stringResource(R.string.command_continue_in, chip.title)) })
}

private fun openSkill(context: android.content.Context, app: MasalaApp, chip: SkillChip, prompt: String) {
    context.startActivity(SkillSheetActivity.intent(context, chip.id, prompt))
    app.commands.dismiss()
}
