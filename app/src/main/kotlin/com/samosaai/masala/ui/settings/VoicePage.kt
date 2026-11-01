package com.samosaai.masala.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.samosaai.masala.R
import com.samosaai.masala.agent.SpeechPolicy
import com.samosaai.masala.agent.SpeechStrings
import com.samosaai.masala.agent.friendlySttMessage
import com.samosaai.masala.audio.AudioProvider
import com.samosaai.masala.core.ai.Endpoint
import com.samosaai.masala.core.ai.KeyKind
import com.samosaai.masala.core.ai.ProviderPresets
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.i18n.Language
import com.samosaai.masala.masala
import com.samosaai.masala.ui.components.Radii
import com.samosaai.masala.ui.theme.MasalaMono
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Settings → Masala AI → Voice (plan UI refresh U2, "Voice in & out" board): how the mic button works,
 * where voice input goes and in which language, the voice replies are read in (the phone's or a
 * provider's), its speed, read-aloud, a test, and Masala as the assist app.
 */
@Composable
fun VoicePage(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.masala
    val store = app.aiSettings
    val profile by store.profile.collectAsState()
    val scope = rememberCoroutineScope()
    val voiceTint = MasalaTheme.tokens.voice

    SettingsScaffold(stringResource(R.string.voice_title), onBack) {
        Eyebrow(stringResource(R.string.voice_mic_button))
        // One rule: tap the mic to listen, Send to end the take. No hold, no auto-send.
        Panel(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Mic, null, tint = voiceTint, modifier = Modifier.size(22.dp))
                Column {
                    Text(stringResource(R.string.voice_tap), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.voice_tap_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Eyebrow(stringResource(R.string.voice_input))
        Panel {
            OptionRow(stringResource(R.string.voice_on_phone), stringResource(R.string.voice_on_phone_summary), profile.voiceIn == null, badge = stringResource(R.string.voice_private)) {
                scope.launch { store.update { it.copy(voiceIn = null) } }
            }
            // Providers that transcribe: the one in use, then any with a key or none needed and a known URL.
            ProviderPresets.all.filter { it.capabilities.voiceIn }.forEach { p ->
                val known = profile.endpoints.firstOrNull { it.presetId == p.id }?.baseUrl ?: p.baseUrl
                val ready = known.isNotBlank() && (p.keyKind == KeyKind.NONE || store.hasKey(p.id))
                val sub = when {
                    profile.voiceIn?.presetId == p.id -> profile.voiceIn?.model?.ifBlank { stringResource(R.string.voice_first_model) } ?: ""
                    !ready && known.isBlank() -> stringResource(R.string.voice_set_up_first)
                    !ready -> stringResource(R.string.voice_needs_key, p.name)
                    else -> stringResource(R.string.voice_from_models)
                }
                OptionRow(p.name, sub, profile.voiceIn?.presetId == p.id, enabled = ready) {
                    scope.launch { store.update { it.copy(voiceIn = Endpoint(p.id, known)) } }
                }
            }
            LanguageRow(profile.voiceLanguage) { code -> scope.launch { store.update { it.copy(voiceLanguage = code) } } }
            SttTestCard()
        }

        Eyebrow(stringResource(R.string.voice_output))
        Panel(Modifier.padding(14.dp)) {
            val outs = ProviderPresets.all.filter { p ->
                p.capabilities.voiceOut && (profile.endpoints.any { it.presetId == p.id } || (p.baseUrl.isNotBlank() && store.hasKey(p.id)))
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.06f)).padding(3.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Segment(stringResource(R.string.voice_phone_voice), profile.voiceOut == null) { scope.launch { store.update { it.copy(voiceOut = null, voice = "") } } }
                outs.forEach { p ->
                    Segment(p.name, profile.voiceOut?.presetId == p.id) {
                        val known = profile.endpoints.firstOrNull { it.presetId == p.id }?.baseUrl ?: p.baseUrl
                        scope.launch { store.update { it.copy(voiceOut = Endpoint(p.id, known, p.defaultTtsModel), voice = "") } }
                    }
                }
            }
            VoiceChips(profile.voiceOut, profile.voiceLanguage, profile.voice) { name -> scope.launch { store.update { it.copy(voice = name) } } }

            var speed by remember(profile.speechRate) { mutableFloatStateOf(profile.speechRate) }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.voice_speed), style = MaterialTheme.typography.titleSmall)
                    Text("%.1f×".format(Locale.ROOT, speed), fontFamily = MasalaMono, color = voiceTint)
                }
                Slider(
                    speed, { speed = (it * 10).toInt() / 10f },
                    valueRange = 0.5f..2f,
                    onValueChangeFinished = { scope.launch { store.update { it.copy(speechRate = speed) } } },
                    colors = SliderDefaults.colors(thumbColor = MasalaTheme.tokens.textHigh, activeTrackColor = voiceTint),
                )
            }
            SettingsToggleRow(stringResource(R.string.settings_read_replies), settings.readRepliesAloud) { on -> update { it.copy(readRepliesAloud = on) } }
            val testSentence = stringResource(R.string.settings_test_voice_sentence)
            val testVoiceOk = stringResource(R.string.settings_test_voice_ok)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val reason = app.speakReply(testSentence)
                        Toast.makeText(context, reason ?: testVoiceOk, Toast.LENGTH_SHORT).show()
                    }
                },
                border = BorderStroke(1.dp, voiceTint.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = voiceTint)
                Text(stringResource(R.string.voice_test), color = voiceTint, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            }
        }

        Eyebrow(stringResource(R.string.voice_from_anywhere))
        Panel {
            com.samosaai.masala.ui.components.SettingsRow(
                Icons.Rounded.AutoAwesome,
                stringResource(R.string.voice_assist_title),
                summary = stringResource(R.string.voice_assist_summary),
                // The system's "Digital assistant app" screen; the assistant role can't be asked for in a dialog.
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            ) { Text(stringResource(R.string.voice_set_up), color = MasalaTheme.tokens.accentText, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Radii.card)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, MasalaTheme.tokens.hairline, shape).then(modifier),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun OptionRow(title: String, sub: String, checked: Boolean, enabled: Boolean = true, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(checked, null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = MasalaTheme.tokens.voice))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MasalaTheme.tokens.textLow)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        badge?.let {
            Text(
                it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MasalaTheme.tokens.done,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(MasalaTheme.tokens.done.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun LanguageRow(code: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val auto = stringResource(R.string.voice_language_auto)
    val current = Language.entries.firstOrNull { it.matchesCode(code) }?.label ?: auto
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.voice_language), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(current, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(auto) }, onClick = { open = false; onPick("") })
            Language.entries.forEach { l -> DropdownMenuItem(text = { Text(l.label) }, onClick = { open = false; onPick(l.iso639) }) }
        }
    }
}

@Composable
private fun Segment(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(50)).background(if (on) MasalaTheme.tokens.textHigh else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick).semantics { selected = on }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) MaterialTheme.colorScheme.surface else MasalaTheme.tokens.textMedium)
    }
}

/** A few voices to pick from: the phone's for the chosen language, or the provider's (from its models). */
@Composable
private fun VoiceChips(out: Endpoint?, languageCode: String, chosen: String, onPick: (String) -> Unit) {
    val app = LocalContext.current.masala
    val language = Language.entries.firstOrNull { it.matchesCode(languageCode.ifBlank { Locale.getDefault().language }) } ?: Language.ENGLISH
    var voices by remember(out, language) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(out, language) { voices = app.voicesFor(out, language) }
    if (voices.isEmpty()) return
    val tint = MasalaTheme.tokens.voice
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        voices.take(8).forEach { v ->
            val on = v == chosen || (chosen.isBlank() && v == voices.first())
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(50))
                    .background(if (on) tint.copy(alpha = 0.12f) else Color.Transparent)
                    .border(if (on) 1.5.dp else 1.dp, if (on) tint else Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                    .clickable(role = Role.RadioButton) { onPick(v) }.semantics { selected = on }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (out == null) phoneVoiceLabel(v) else v, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
        }
    }
}

/** "en-AU-language" or "en-in-x-ene-local" → "English (Australia)"; engines name voices by locale first. */
private fun phoneVoiceLabel(name: String): String {
    val tag = name.split('-').take(2).joinToString("-")
    val variant = name.substringAfter("-x-", "").substringBefore('-').takeIf { it.isNotBlank() }
    val display = Locale.forLanguageTag(tag).displayName.takeIf { it.isNotBlank() && it != tag } ?: return name
    return if (variant != null) "$display · $variant" else display
}

/**
 * Settings → Voice → microphone test: runs the same utterance path the mic button uses and
 * reports the live mic level, the peak, and the transcript or the mapped failure — so a dead
 * mic, a deaf detector, and a bad server each show up as themselves.
 */
@Composable
private fun SttTestCard() {
    val context = LocalContext.current
    val app = context.masala
    val engine = remember { app.sttEngine() }
    val scope = rememberCoroutineScope()
    val voiceTint = MasalaTheme.tokens.voice
    var testing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var heard by remember { mutableStateOf<String?>(null) }
    var peak by remember { mutableFloatStateOf(0f) }
    var activeProvider by remember { mutableStateOf<AudioProvider?>(null) }
    var usedOnDevice by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    var userCancelled by remember { mutableStateOf(false) }
    val level by engine.level.collectAsState()
    val partial by engine.partial.collectAsState()
    val vad by engine.vadState.collectAsState()
    LaunchedEffect(testing, level) { if (testing && level > peak) peak = level }
    val strings = SpeechStrings(
        localUnavailable = stringResource(R.string.speech_local_unavailable),
        recordFailed = stringResource(R.string.speech_record_failed),
        noSpeech = stringResource(R.string.stt_no_speech),
        didntCatch = stringResource(R.string.stt_didnt_catch),
        busy = stringResource(R.string.stt_error_busy),
        server = stringResource(R.string.stt_error_server),
        auth = stringResource(R.string.stt_error_auth),
        recordError = stringResource(R.string.stt_error_record),
        generic = stringResource(R.string.stt_error_generic),
    )

    fun start(requestGrant: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestGrant()
            return
        }
        if (testing) return
        testing = true
        userCancelled = false
        outcome = null
        heard = null
        detail = null
        usedOnDevice = false
        peak = 0f
        scope.launch {
            try {
                val cfg = app.aiSettings.speechConfig()
                val code = cfg.sttLanguage.ifBlank { Locale.getDefault().language }
                val lang = Language.entries.firstOrNull { it.matchesCode(code) } ?: Language.ENGLISH
                // Same order and fallback the mic button uses, so the test reports what
                // a real tap would do, not a rosier single-provider attempt.
                val order = SpeechPolicy.order(if (cfg.baseUrl.isNotBlank()) AudioProvider.API else AudioProvider.ANDROID)
                    .filter { it != AudioProvider.ANDROID || engine.isLocalRecognitionAvailable() }
                if (order.isEmpty()) {
                    if (!userCancelled) outcome = strings.localUnavailable
                    return@launch
                }
                var lastResult: Result<String>? = null
                var lastFailure: String? = null
                // Why a configured server failed, kept apart from lastFailure: a fallback
                // that then hears nothing must still be able to name the server problem.
                var serverFailure: String? = null
                for (provider in order) {
                    activeProvider = provider
                    val model = if (provider.isApiBased()) cfg.sttModel else ""
                    val result = withTimeoutOrNull(45_000L) { engine.listenForUtterance(provider, model, lang, 2_500L) }
                    if (userCancelled) return@launch
                    if (result == null) {
                        lastFailure = strings.server
                        if (provider.isApiBased()) serverFailure = strings.server
                        continue
                    }
                    if (result.isSuccess) {
                        lastResult = result
                        // Only a stand-in when a configured server actually failed first.
                        usedOnDevice = provider == AudioProvider.ANDROID && serverFailure != null
                        break
                    }
                    lastFailure = result.exceptionOrNull()?.message
                    if (provider.isApiBased()) serverFailure = lastFailure
                    // Silence is final: another provider would only repeat the wait.
                    if (com.samosaai.masala.audio.SttEngine.isBenignSttError(result.exceptionOrNull())) {
                        lastResult = result
                        break
                    }
                }
                lastResult?.onSuccess {
                    if (it.isBlank()) outcome = strings.didntCatch else heard = it
                }?.onFailure {
                    outcome = friendlySttMessage(it.message, strings)
                }
                // A configured server that failed first is the likelier cause than
                // silence, so say it under the outcome — once, never as a repeat.
                serverFailure?.let { f ->
                    val extra = friendlySttMessage(f, strings)
                    if (extra != outcome) detail = extra
                }
                if (!userCancelled && lastResult == null) {
                    outcome = friendlySttMessage(lastFailure, strings)
                }
            } catch (e: Exception) {
                if (!userCancelled) outcome = friendlySttMessage(e.message, strings)
            } finally {
                activeProvider = null
                testing = false
            }
        }
    }

    val grant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start({}) else outcome = strings.recordError
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.stt_test_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!testing && heard == null && outcome == null) {
            OutlinedButton(
                onClick = { start { grant.launch(Manifest.permission.RECORD_AUDIO) } },
                border = BorderStroke(1.dp, voiceTint.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Icon(Icons.Rounded.Mic, null, tint = voiceTint)
                Text(stringResource(R.string.stt_test), color = voiceTint, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (testing) {
            val liveText = partial.takeIf { it.isNotBlank() }?.let { " — “$it”" } ?: ""
            Text(
                stringResource(R.string.stt_test_listening) + liveText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.10f))) {
                Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(voiceTint))
            }
            Text(stringResource(R.string.stt_test_peak, (peak.coerceIn(0f, 1f) * 100).toInt()), fontFamily = MasalaMono, style = MaterialTheme.typography.bodySmall, color = voiceTint)
            vad?.let {
                Text(
                    stringResource(R.string.stt_test_vad, it.floorDb.toInt(), it.thresholdDb.toInt(), it.peakDb.toInt()),
                    fontFamily = MasalaMono, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { engine.finishUtterance() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.stt_test_stop)) }
                OutlinedButton(
                    onClick = {
                        userCancelled = true
                        activeProvider?.let { engine.cancelListening(it) }
                        testing = false
                        outcome = null
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.stt_test_cancel)) }
            }
        }
        heard?.let {
            Text(stringResource(R.string.stt_test_heard, it), style = MaterialTheme.typography.bodyMedium)
            if (usedOnDevice) {
                Text(
                    stringResource(R.string.stt_test_fell_back),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { start { grant.launch(Manifest.permission.RECORD_AUDIO) } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.stt_test))
            }
        }
        outcome?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            detail?.let { d ->
                Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { start { grant.launch(Manifest.permission.RECORD_AUDIO) } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.stt_test))
            }
        }
    }
}
