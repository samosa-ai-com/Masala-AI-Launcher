package com.samosaai.masala.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.chat.AttachedImage
import com.samosaai.masala.chat.AttachmentStrip
import com.samosaai.masala.ui.home.MicButton
import com.samosaai.masala.ui.theme.MasalaTheme

/**
 * The one composer for asking Masala (plan `docs/plans/reply-sheet-refresh.md`): a filled pill with the
 * attach pin on the left, the text, and on the right the mic plus a gold Send while there is something
 * to send, or the mic alone in the gold circle while it is empty. Attached photos sit inside the pill
 * above the text. The pin opens a small tray (Photo, Camera) and turns into its close button.
 *
 * Text and images belong to the caller, which also runs the pickers; the pill only owns the tray.
 */
@Composable
fun ComposerPill(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    images: List<AttachedImage>,
    onRemoveImage: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    /** For the caller's focus request; applied to the text field. */
    fieldModifier: Modifier = Modifier,
    micLevel: () -> Float = { 0f },
    placeholder: String = stringResource(R.string.command_hint),
    /**
     * False when the caller cannot carry a pending draft into listening: tapping the mic would
     * leave the typed text and photos behind. The surface that keeps its draft leaves this true.
     */
    micEnabled: Boolean = true,
) {
    val tokens = MasalaTheme.tokens
    var tray by remember { mutableStateOf(false) }
    val canSend = value.text.isNotBlank() || images.isNotEmpty()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedVisibility(tray, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            AttachTray(
                onPhoto = { tray = false; onPickPhoto() },
                onCamera = { tray = false; onTakePhoto() },
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (images.isNotEmpty()) {
                // Lined up with the text, not the pin.
                Box(Modifier.padding(start = 46.dp, top = 4.dp)) { AttachmentStrip(images, onRemove = onRemoveImage) }
            }
            // Bottom-aligned: as the text grows the buttons stay on its last line.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PinButton(open = tray) { tray = !tray }
                BasicTextField(
                    value,
                    onValueChange,
                    Modifier.weight(1f).padding(vertical = 9.dp).then(fieldModifier),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(tokens.accent),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    decorationBox = { field ->
                        Box {
                            if (value.text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = tokens.textMedium)
                            field()
                        }
                    },
                )
                if (canSend) {
                    // The mic stays reachable with text in the field: switch to voice mid-sentence.
                    // 48 dp of touch target around a 40 dp circle, so mic and Send don't overlap.
                    IconButton(onClick = onMic, enabled = micEnabled, modifier = Modifier.size(48.dp)) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Mic, stringResource(R.string.voice_tap), tint = tokens.textMedium)
                        }
                    }
                    IconButton(onClick = onSend, modifier = Modifier.size(48.dp)) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(tokens.accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.skill_send), tint = tokens.onAccent, modifier = Modifier.size(20.dp))
                        }
                    }
                } else {
                    MicButton(onMic, level = micLevel, size = 48.dp)
                }
            }
        }
    }
}

/** The pin; a close button while the tray is open. */
@Composable
private fun PinButton(open: Boolean, onClick: () -> Unit) {
    val tokens = MasalaTheme.tokens
    val label = stringResource(if (open) R.string.composer_attach_close else R.string.composer_attach)
    // 48 dp target around the 40 dp circle.
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (open) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (open) Icons.Rounded.Close else Icons.Rounded.AttachFile, label, tint = if (open) MaterialTheme.colorScheme.onSurface else tokens.textMedium)
        }
    }
}

/** Photo and Camera as round buttons with a label under each, in a small floating capsule. */
@Composable
private fun AttachTray(onPhoto: () -> Unit, onCamera: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.padding(start = 2.dp).shadow(10.dp, shape).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MasalaTheme.tokens.hairline, shape).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TrayButton(Icons.Rounded.Image, stringResource(R.string.composer_photo), onPhoto)
        TrayButton(Icons.Rounded.PhotoCamera, stringResource(R.string.composer_camera), onCamera)
    }
}

@Composable
private fun TrayButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
        // The label names the button for TalkBack too.
        Text(label, style = MaterialTheme.typography.labelMedium, color = MasalaTheme.tokens.textMedium)
    }
}
