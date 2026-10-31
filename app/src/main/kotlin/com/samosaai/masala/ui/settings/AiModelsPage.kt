package com.samosaai.masala.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samosaai.masala.R
import com.samosaai.masala.core.ai.AiProfile
import com.samosaai.masala.core.ai.AiValidation
import com.samosaai.masala.core.ai.Endpoint
import com.samosaai.masala.core.ai.KeyKind
import com.samosaai.masala.core.ai.ProviderPreset
import com.samosaai.masala.core.ai.ProviderPresets
import com.samosaai.masala.llm.AiConnection
import com.samosaai.masala.masala
import com.samosaai.masala.ui.components.Radii
import com.samosaai.masala.ui.theme.MasalaMono
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.theme.rememberHaptics
import kotlinx.coroutines.launch

/**
 * Settings → Masala AI → Models & keys (plan UI refresh U2, "Models & keys" board): pick a provider and
 * its URL is filled in; paste a key (kept encrypted); fetch the model list; test the connection; choose
 * what this provider is used for. Chat, voice in and voice out can each use a different provider.
 */
@Composable
fun AiModelsPage(onBack: () -> Unit) {
    val app = LocalContext.current.masala
    val store = app.aiSettings
    val profile by store.profile.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var pickedId by rememberSaveable { mutableStateOf(profile.chat.presetId) }
    val preset = ProviderPresets.byId(pickedId)
    // The endpoint this preset already has in the profile, if any role uses it.
    val inUse = profile.endpoints.firstOrNull { it.presetId == preset.id }

    var host by rememberSaveable(pickedId) { mutableStateOf(inUse?.baseUrl?.substringAfter("://")?.substringBefore(':').orEmpty()) }
    var url by rememberSaveable(pickedId) { mutableStateOf(inUse?.baseUrl ?: preset.baseUrl) }
    var key by rememberSaveable(pickedId) { mutableStateOf(store.key(preset.id)) }
    var reveal by remember(pickedId) { mutableStateOf(false) }
    var model by rememberSaveable(pickedId) { mutableStateOf(if (profile.chat.presetId == preset.id) profile.chat.model else preset.defaultChatModel) }
    var models by remember(pickedId) { mutableStateOf<List<String>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var outcome by remember(pickedId) { mutableStateOf<AiConnection.Outcome?>(null) }

    val effectiveUrl = if (preset.onLan) preset.baseUrlFor(host) else url
    val urlCheck = AiValidation.normalizeBaseUrl(effectiveUrl)
    val clipboard = LocalClipboardManager.current

    /** Writes the key and refreshes every role this preset is used for. The key is saved even when
     *  the URL doesn't validate yet, so backing out never loses a pasted key. */
    suspend fun persist(roles: (AiProfile) -> AiProfile = { it }) {
        if (preset.keyKind == KeyKind.KEY) store.setKey(preset.id, key)
        val base = urlCheck.getOrNull() ?: return
        store.update { p ->
            fun Endpoint.refreshed(m: String = model) = if (presetId == preset.id) copy(baseUrl = base, model = m) else this
            roles(
                p.copy(
                    chat = p.chat.refreshed(),
                    voiceIn = p.voiceIn?.let { it.refreshed(it.model) },
                    voiceOut = p.voiceOut?.let { it.refreshed(it.model) },
                ),
            )
        }
    }

    // Typed input must survive every exit, not just Test/Toggle: the activity-level BackHandler
    // would otherwise pop this page without flushing.
    BackHandler { scope.launch { persist(); onBack() } }

    SettingsScaffold(stringResource(R.string.ai_models_title), { scope.launch { persist(); onBack() } }) {
        Text(stringResource(R.string.ai_models_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Eyebrow(stringResource(R.string.ai_models_provider))
        PresetGrid(current = preset.id) { scope.launch { persist(); pickedId = it; haptics.tick() } }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.card)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MasalaTheme.tokens.hairline, RoundedCornerShape(Radii.card)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(preset.name, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
                Text(presetNote(preset), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CapChip(preset.capabilities.chat, stringResource(R.string.ai_cap_chat))
                CapChip(preset.capabilities.voiceIn, stringResource(R.string.ai_cap_voice_in))
                CapChip(preset.capabilities.voiceOut, stringResource(R.string.ai_cap_voice_out))
            }
            if (preset.onLan) {
                OutlinedTextField(
                    host, { host = it.trim() },
                    label = { Text(stringResource(R.string.ai_computer_address)) },
                    placeholder = { Text("192.168.1.20") },
                    supportingText = { Text(preset.baseUrlFor(host.ifBlank { "…" }), fontFamily = MasalaMono) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    url, { url = it },
                    label = { Text(stringResource(R.string.ai_base_url)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MasalaMono),
                    isError = urlCheck.isFailure && url.isNotBlank(),
                    supportingText = urlCheck.exceptionOrNull()?.let { e -> { Text(urlProblem((e as? AiValidation.InvalidUrl)?.problem?.name ?: "EMPTY")) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (preset.keyKind == KeyKind.KEY) {
                OutlinedTextField(
                    key, { key = it.trim() },
                    label = { Text(stringResource(R.string.ai_api_key)) },
                    placeholder = { Text(stringResource(R.string.ai_paste_key)) },
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MasalaMono),
                    singleLine = true,
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { reveal = !reveal }) {
                                Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(if (reveal) R.string.ai_hide_key else R.string.ai_show_key))
                            }
                            TextButton(onClick = { clipboard.getText()?.text?.trim()?.let { key = it } }) { Text(stringResource(R.string.ai_paste)) }
                        }
                    },
                    supportingText = { Text(stringResource(R.string.ai_key_note)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(stringResource(R.string.ai_no_key_needed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (preset.capabilities.chat) {
                Box {
                    OutlinedTextField(
                        model, { model = it.trim() },
                        label = { Text(stringResource(R.string.ai_chat_model)) },
                        placeholder = { Text(stringResource(R.string.ai_fetch_hint)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MasalaMono),
                        singleLine = true,
                        trailingIcon = {
                            TextButton(enabled = urlCheck.isSuccess, onClick = {
                                scope.launch {
                                    when (val o = app.aiConnection.test(urlCheck.getOrThrow(), key)) {
                                        is AiConnection.Outcome.Connected -> { models = o.found.chat; menu = models.isNotEmpty() }
                                        else -> outcome = o
                                    }
                                }
                            }) { Text(stringResource(R.string.ai_fetch_list)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        models.forEach { id ->
                            DropdownMenuItem(text = { Text(id, fontFamily = MasalaMono) }, onClick = {
                                model = id
                                menu = false
                                scope.launch { persist() }
                            })
                        }
                    }
                }
            }

            Column {
                Text(stringResource(R.string.ai_use_for, preset.name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                UseRow(stringResource(R.string.ai_use_chat), profile.chat.presetId == preset.id, preset.capabilities.chat || preset.id == "custom") { on ->
                    // Chat always has a provider: switching it on moves chat here; off is done by picking another.
                    if (on) scope.launch { persist { it.copy(chat = Endpoint(preset.id, urlCheck.getOrThrow(), model)) } }
                }
                UseRow(stringResource(R.string.ai_use_voice_in), profile.voiceIn?.presetId == preset.id, preset.capabilities.voiceIn) { on ->
                    scope.launch { persist { it.copy(voiceIn = if (on) Endpoint(preset.id, urlCheck.getOrThrow()) else null) } }
                }
                UseRow(stringResource(R.string.ai_use_voice_out), profile.voiceOut?.presetId == preset.id, preset.capabilities.voiceOut) { on ->
                    scope.launch {
                        persist {
                            val model = it.voiceOut?.takeIf { v -> v.presetId == preset.id }?.model?.ifBlank { preset.defaultTtsModel }
                                ?: preset.defaultTtsModel
                            // A phone voice picked earlier would 400 on the API; start unpicked.
                            it.copy(voiceOut = if (on) Endpoint(preset.id, urlCheck.getOrThrow(), model) else null, voice = if (on) "" else it.voice)
                        }
                    }
                }
            }

            Button(
                onClick = {
                    testing = true
                    scope.launch {
                        persist()
                        outcome = app.aiConnection.test(urlCheck.getOrThrow(), key)
                        testing = false
                        if (outcome is AiConnection.Outcome.Connected) haptics.confirm() else haptics.reject()
                    }
                },
                enabled = urlCheck.isSuccess && !testing,
                colors = ButtonDefaults.buttonColors(containerColor = MasalaTheme.tokens.accent, contentColor = MasalaTheme.tokens.onAccent),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(stringResource(if (testing) R.string.ai_testing else R.string.ai_test_connection), fontWeight = FontWeight.Bold) }

            outcome?.let { TestResult(it) }
        }

        // Loop limits: how far one request may go, and when a repeating tool stops it.
        Eyebrow(stringResource(R.string.ai_limits))
        AgentLimits(profile) { change -> scope.launch { persist(change) } }
        LaunchedEffect(pickedId) { outcome = null }
    }
}

/**
 * Two numbers that decide how far a single request can go: the model rounds it may take,
 * and how many identical tool calls in a row mean it is stuck. Both apply to spoken
 * commands and skill runs.
 */
@Composable
private fun AgentLimits(profile: com.samosaai.masala.core.ai.AiProfile, onChange: ((com.samosaai.masala.core.ai.AiProfile) -> com.samosaai.masala.core.ai.AiProfile) -> Unit) {
    var rounds by remember(profile.maxToolRounds) { mutableStateOf(profile.maxToolRounds.toString()) }
    var repeats by remember(profile.maxRepeatedToolCalls) { mutableStateOf(profile.maxRepeatedToolCalls.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            rounds, { rounds = it.filter(Char::isDigit).take(3) },
            label = { Text(stringResource(R.string.ai_max_rounds)) },
            supportingText = { Text(stringResource(R.string.ai_max_rounds_note)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            repeats, { repeats = it.filter(Char::isDigit).take(2) },
            label = { Text(stringResource(R.string.ai_max_repeats)) },
            supportingText = { Text(stringResource(R.string.ai_max_repeats_note)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            enabled = rounds.toIntOrNull() != null && repeats.toIntOrNull() != null,
            onClick = {
                val r = (rounds.toIntOrNull() ?: profile.maxToolRounds)
                    .coerceIn(com.samosaai.masala.core.ai.AiProfile.MIN_ROUNDS, com.samosaai.masala.core.ai.AiProfile.MAX_ROUNDS)
                val p = (repeats.toIntOrNull() ?: profile.maxRepeatedToolCalls)
                    .coerceIn(com.samosaai.masala.core.ai.AiProfile.MIN_REPEATS, com.samosaai.masala.core.ai.AiProfile.MAX_REPEATS)
                rounds = r.toString()
                repeats = p.toString()
                onChange { it.copy(maxToolRounds = r, maxRepeatedToolCalls = p) }
            },
        ) { Text(stringResource(R.string.ai_save_limits)) }
    }
}

@Composable
internal fun Eyebrow(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
private fun PresetGrid(current: String, onPick: (String) -> Unit) {
    val tokens = MasalaTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderPresets.all.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    val on = p.id == current
                    val shape = RoundedCornerShape(18.dp)
                    Column(
                        Modifier.weight(1f).clip(shape)
                            .background(if (on) tokens.accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(BorderStroke(1.5.dp, if (on) tokens.accent else tokens.hairline), shape)
                            .clickable(role = Role.RadioButton) { onPick(p.id) }
                            .semantics { selected = on }
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(if (on) tokens.accent else Color.White.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center,
                        ) { Text(p.mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) tokens.onAccent else MaterialTheme.colorScheme.onSurface) }
                        Text(p.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        Text(caps(p), style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun caps(p: ProviderPreset): String = listOfNotNull(
    stringResource(R.string.ai_cap_chat).takeIf { p.capabilities.chat },
    stringResource(R.string.ai_cap_in).takeIf { p.capabilities.voiceIn },
    stringResource(R.string.ai_cap_out).takeIf { p.capabilities.voiceOut },
).joinToString(" · ")

@Composable
private fun presetNote(p: ProviderPreset): String = stringResource(
    when (p.id) {
        "openai" -> R.string.ai_note_openai
        "meta" -> R.string.ai_note_meta
        "anthropic" -> R.string.ai_note_anthropic
        "gemini" -> R.string.ai_note_chat_only
        "groq" -> R.string.ai_note_groq
        "openrouter" -> R.string.ai_note_openrouter
        "mistral" -> R.string.ai_note_mistral
        "lmstudio", "ollama" -> R.string.ai_note_lan
        "speaches" -> R.string.ai_note_speaches
        else -> R.string.ai_note_custom
    },
)

@Composable
private fun urlProblem(name: String): String = stringResource(
    when (name) {
        "NOT_HTTP" -> R.string.ai_url_not_http
        "NO_HOST" -> R.string.ai_url_no_host
        "HAS_QUERY" -> R.string.ai_url_query
        else -> R.string.ai_url_empty
    },
)

@Composable
private fun CapChip(ok: Boolean, label: String) {
    val tokens = MasalaTheme.tokens
    Row(
        Modifier.height(28.dp).clip(RoundedCornerShape(50)).background(if (ok) tokens.done.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.Remove, null, Modifier.size(14.dp), tint = if (ok) tokens.done else tokens.textLow)
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (ok) tokens.done else tokens.textLow)
    }
}

@Composable
private fun UseRow(label: String, checked: Boolean, offered: Boolean, onChange: (Boolean) -> Unit) {
    val text = if (offered) label else stringResource(R.string.ai_not_offered, label)
    Box(Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
        if (offered) {
            SettingsToggleRow(text, checked, onChange)
        } else {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MasalaTheme.tokens.textLow, modifier = Modifier.align(Alignment.CenterStart))
        }
    }
}

@Composable
private fun TestResult(outcome: AiConnection.Outcome) {
    val tokens = MasalaTheme.tokens
    val (ok, title, line) = when (outcome) {
        is AiConnection.Outcome.Connected -> {
            val f = outcome.found
            val parts = listOfNotNull(
                stringResource(R.string.ai_found_chat, f.chat.size).takeIf { f.chat.isNotEmpty() },
                stringResource(R.string.ai_found_stt, f.transcription.size).takeIf { f.transcription.isNotEmpty() },
                pluralStringResource(R.plurals.ai_found_tts, f.voices.size, f.voices.size).takeIf { f.voices.isNotEmpty() },
            ).ifEmpty { listOf(stringResource(R.string.ai_found_nothing)) }
            Triple(true, stringResource(R.string.ai_connected), parts.joinToString(", ") + " · ${f.millis} ms")
        }
        is AiConnection.Outcome.KeyRefused -> Triple(false, stringResource(R.string.ai_key_refused), "HTTP ${outcome.code}")
        is AiConnection.Outcome.Failed -> Triple(false, stringResource(R.string.ai_not_connected), outcome.reason)
    }
    val color = if (ok) tokens.done else tokens.alert
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; contentDescription = "$title. $line" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.ErrorOutline, null, tint = color)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
