package com.samosaai.masala.ui.home

import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.components.Radii
import com.samosaai.masala.ui.components.wallpaperGlass
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
import com.samosaai.masala.ui.components.Markdown
import com.samosaai.masala.ui.components.rememberCapabilityGrant
import com.samosaai.masala.ui.secondbrain.RememberedCards
import kotlinx.coroutines.launch

/**
 * What a tap on the mic does, with the microphone permission asked on the first tap:
 * opens Sam listening; Send (or cancel) ends the take. There is no hold: a tap is
 * `onClick`, which completes before any state change unmounts the button, so the
 * release can never be swallowed mid-take.
 */
@Composable
fun rememberMicTap(app: MasalaApp): () -> Unit {
    val context = LocalContext.current
    val granted = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* the next tap listens */ }
    // Keyed on the context, launcher and app: keying on the app singleton would keep the
    // first composition's dead permission launcher and destroyed Activity across rotations.
    return remember(context, granted, app) {
        {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (app.commands.state.value != CommandState.Listening) app.commands.startListening()
            } else {
                granted.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

/**
 * "Search or ask Masala" with the mic (UI refresh U3, home boards): one glass pill above the dock in
 * place of the two floating buttons. Tapping the pill opens search; the mic talks.
 */
@Composable
fun SearchPill(onSearch: () -> Unit, mic: () -> Unit, onOpenChat: () -> Unit = {}, modifier: Modifier = Modifier, level: () -> Float = { 0f }) {
    val tokens = MasalaTheme.tokens
    val shape = RoundedCornerShape(50)
    val label = stringResource(R.string.home_search_or_ask)
    Row(
        modifier.fillMaxWidth().padding(horizontal = HOME_GUTTER, vertical = 6.dp).height(58.dp)
            .wallpaperGlass(shape)
            // Tap searches; long-press opens the conversation (history included), so the
            // chat is reachable while nothing is running.
            .combinedClickable(role = Role.Button, onClickLabel = label, onClick = onSearch, onLongClick = onOpenChat, onLongClickLabel = stringResource(R.string.chat_history))
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = tokens.onWallpaperMedium, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tokens.onWallpaperMedium, modifier = Modifier.weight(1f))
        // The conversation is reachable without the microphone: typing, reading history,
        // or continuing an earlier chat.
        IconButton(onClick = onOpenChat, modifier = Modifier.size(46.dp)) {
            Icon(
                Icons.Rounded.ChatBubbleOutline,
                stringResource(R.string.chat_open),
                tint = tokens.onWallpaperMedium,
                modifier = Modifier.size(22.dp),
            )
        }
        MicButton(mic, level = level, size = 46.dp)
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

/** The mic: one tap opens Sam listening; Send ends the take. */
@Composable
fun MicButton(onTap: () -> Unit, level: () -> Float = { 0f }, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val label = stringResource(R.string.voice_tap)
    val ring = MasalaTheme.tokens.accent
    Surface(
        onClick = onTap,
        modifier
            .size(size)
            .drawBehind {
                // Draw-phase read only: the mic level redraws the ring without recomposing.
                val l = level().coerceIn(0f, 1f)
                if (l > 0.01f) {
                    drawCircle(
                        color = ring.copy(alpha = 0.25f + 0.55f * l),
                        radius = size.toPx() / 2f * (1f + 0.28f * l),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = (2f + 2f * l).dp.toPx()),
                    )
                }
            }
            .semantics { contentDescription = label; role = Role.Button },
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
    val minimized by app.commands.minimized.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val micTap = rememberMicTap(app)
    val micLvl by app.commands.micLevel.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val retryPrompt = stringResource(R.string.capability_retry_prompt)
    // A dialog that was allowed carries on by itself; a settings screen needs Try again.
    val grant = rememberCapabilityGrant { granted -> if (granted) app.commands.ask(retryPrompt) }

    val reply = state as? CommandState.Reply
    // "Read replies aloud" applies to every reply; the button reads one on request.
    LaunchedEffect(reply) { if (readAloud && reply != null && app.commands.claimReadAloud(reply)) app.speakReply(reply.text) }
    if (state == CommandState.Idle || minimized) return
    if (sheets && (state == CommandState.Listening || state is CommandState.Thinking)) return

    // A glass sheet over home (UI refresh U3); opened for typing, its field takes focus.
    val field = remember { FocusRequester() }
    LaunchedEffect(state) {
        if (state == CommandState.Input) {
            app.commands.takeDraft().takeIf { it.isNotEmpty() }?.let { text = TextFieldValue(it, TextRange(it.length)) }
            runCatching { field.requestFocus() }
        }
    }
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
                    CommandState.ListeningPaused -> Text(stringResource(R.string.voice_paused), style = MaterialTheme.typography.titleMedium)
                    is CommandState.Thinking -> {
                        if (s.prompt.isNotBlank()) Text(s.prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.command_thinking), style = MaterialTheme.typography.titleMedium)
                        Steps(s.steps)
                    }
                    is CommandState.Reply -> {
                        if (s.prompt.isNotBlank()) Text(s.prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Steps(s.steps)
                        Markdown(s.text)
                        RememberedCards(app)
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
                if (state == CommandState.Listening) {
                    // The assist surface has no listening overlay: this Send is the only
                    // way to end the take here. Close would throw it away.
                    Box(Modifier.weight(1f))
                    androidx.compose.material3.Button(onClick = { app.commands.sendNow() }) {
                        Text(stringResource(R.string.chat_send_now))
                    }
                } else if (state == CommandState.ListeningPaused) {
                    androidx.compose.material3.Button(onClick = { app.commands.continueListening() }) {
                        Text(stringResource(R.string.voice_continue))
                    }
                    TextButton(onClick = { app.commands.dismiss() }) {
                        Text(stringResource(R.string.listening_cancel))
                    }
                    Box(Modifier.weight(1f))
                    androidx.compose.material3.Button(onClick = { app.commands.sendNow() }) {
                        Text(stringResource(R.string.chat_send_now))
                    }
                } else {
                    val tooManyText = stringResource(R.string.chat_too_many_images, com.samosaai.masala.chat.MAX_IMAGES_PER_MESSAGE)
                    val badImageText = stringResource(R.string.chat_bad_image)
                    var queued by remember { mutableStateOf(listOf<com.samosaai.masala.chat.AttachedImage>()) }
                    var cameraTarget by remember { mutableStateOf<android.net.Uri?>(null) }
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
                    androidx.compose.material3.IconButton(
                        onClick = {
                            if (queued.size >= com.samosaai.masala.chat.MAX_IMAGES_PER_MESSAGE) {
                                android.widget.Toast.makeText(context, tooManyText, android.widget.Toast.LENGTH_SHORT).show()
                            } else pickImage.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Rounded.AddPhotoAlternate,
                            stringResource(R.string.chat_attach_image),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.IconButton(
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
                        Icon(
                            Icons.Rounded.PhotoCamera,
                            stringResource(R.string.chat_take_photo),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        text,
                        { text = it },
                        Modifier.weight(1f).focusRequester(field),
                        placeholder = { Text(stringResource(R.string.command_hint)) },
                        minLines = 1,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (text.text.isNotBlank() || queued.isNotEmpty()) { app.commands.ask(text.text, images = queued.map { it.jpegBase64 }); text = TextFieldValue(""); queued = emptyList() } }),
                    )
                    if (text.text.isNotBlank() || queued.isNotEmpty()) {
                        TextButton(onClick = { app.commands.ask(text.text, images = queued.map { it.jpegBase64 }); text = TextFieldValue(""); queued = emptyList() }) { Text(stringResource(R.string.skill_send)) }
                    } else {
                        MicButton(micTap, level = { micLvl })
                    }
                    }
                    }
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
                TextButton(onClick = { app.stopSpeaking(); app.commands.minimize() }) { Text(stringResource(R.string.command_close)) }
            }
        }
    }
}

@Composable
private fun Steps(steps: List<String>) {
    steps.forEach { step ->
        val label = if (step.startsWith("Consulting ")) step else stringResource(R.string.skill_step_done, step)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


