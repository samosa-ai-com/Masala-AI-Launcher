package com.samosaai.masala.ui.home

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
import com.samosaai.masala.ui.wiki.ProposalCards
import kotlinx.coroutines.launch

/** Push-to-talk: what a held mic button does, with the microphone permission asked on the first press. */
class MicPress(val onPress: () -> Unit, val onRelease: () -> Unit)

@Composable
fun rememberMicPress(app: MasalaApp): MicPress {
    val context = LocalContext.current
    val granted = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* the next press listens */ }
    return remember(app) {
        MicPress(
            onPress = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    granted.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    app.commands.startListening()
                }
            },
            onRelease = { app.commands.stopListening() },
        )
    }
}

/** The mic: records while held. */
@Composable
fun MicButton(press: MicPress, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.command_mic)
    Surface(
        modifier
            .size(48.dp)
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
        Box(contentAlignment = Alignment.Center) { Text("🎤") }
    }
}

/**
 * The reply card over home or the assist screen (plan Phase 4 M3): what Masala is doing, the reply as
 * Markdown, a skill chip if the request fits a persona, a follow-up field with push-to-talk, and
 * Read aloud. Reads the app's [com.samosaai.masala.agent.CommandController], so a command started on
 * home keeps going if the card is left.
 */
@Composable
fun ReplyCard(app: MasalaApp, readAloud: Boolean, modifier: Modifier = Modifier) {
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

    Card(
        modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Modifier.weight(1f),
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
                if (reply != null) TextButton(onClick = { scope.launch { app.speakReply(reply.text) } }) { Text(stringResource(R.string.command_read_aloud)) }
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
