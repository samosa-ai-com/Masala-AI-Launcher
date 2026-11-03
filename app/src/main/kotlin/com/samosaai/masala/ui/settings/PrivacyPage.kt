package com.samosaai.masala.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.ai.Endpoint
import com.samosaai.masala.masala
import com.samosaai.masala.ui.components.SectionCard
import com.samosaai.masala.ui.components.SettingsRow
import com.samosaai.masala.ui.theme.MasalaMono

/** The host of [url], for "goes to …". */
internal fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').ifBlank { url }

/** Tools that reach the network, whose recent runs the log shows. */
private val NETWORK_TOOLS = setOf("websearch", "webfetch", "weather", "open_url", "transcribe_file")

/**
 * Settings → Privacy & data → What leaves the phone (plan UI refresh U5): for each feature, where its
 * data goes now (from the AI profile and the MCP list), then the recent runs of tools that use the
 * network, from the action log. Everything else stays on the phone.
 */
@Composable
fun PrivacyPage(onBack: () -> Unit) {
    val app = LocalContext.current.masala
    val profile by app.aiSettings.profile.collectAsState()
    val servers by app.mcp.servers.collectAsState()
    val onPhone = stringResource(R.string.privacy_on_phone)
    fun where(e: Endpoint?) = e?.let { "${it.preset.name} · ${hostOf(it.baseUrl)}" } ?: onPhone
    // The log file can be half a megabyte: read it in the background.
    val log by produceState(emptyList<String>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.actionLog.tail(200).filter { line -> line.split('\t').getOrNull(1) in NETWORK_TOOLS || line.split('\t').getOrNull(1)?.startsWith("mcp_") == true }.takeLast(12).reversed()
        }
    }

    SettingsScaffold(stringResource(R.string.privacy_title), onBack) {
        Text(stringResource(R.string.privacy_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionCard(title = stringResource(R.string.privacy_where)) {
            SettingsRow(Icons.Rounded.Chat, stringResource(R.string.privacy_chat), summary = stringResource(R.string.privacy_chat_what, where(profile.chat)))
            SettingsRow(Icons.Rounded.Mic, stringResource(R.string.privacy_voice_in), summary = where(profile.voiceIn))
            SettingsRow(Icons.Rounded.VolumeUp, stringResource(R.string.privacy_voice_out), summary = where(profile.voiceOut))
            SettingsRow(Icons.Rounded.Language, stringResource(R.string.privacy_web), summary = stringResource(R.string.privacy_web_what))
            SettingsRow(Icons.Rounded.WbSunny, stringResource(R.string.brief_title), summary = stringResource(R.string.privacy_brief_what))
            SettingsRow(
                Icons.Rounded.Extension, stringResource(R.string.privacy_mcp),
                summary = servers.filter { it.enabled }.joinToString { "${it.name} · ${hostOf(it.url)}" }.ifBlank { stringResource(R.string.privacy_none) },
            )
            SettingsRow(Icons.Rounded.PhoneAndroid, stringResource(R.string.privacy_local), summary = stringResource(R.string.privacy_local_what))
        }
        SectionCard(title = stringResource(R.string.privacy_recent)) {
            if (log.isEmpty()) {
                Text(stringResource(R.string.privacy_recent_none), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
            log.forEach { line ->
                val parts = line.split('\t')
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("${parts.getOrNull(1).orEmpty()} · ${parts.getOrNull(3).orEmpty()}", style = MaterialTheme.typography.titleSmall)
                    Text(parts.getOrNull(0).orEmpty(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = MasalaMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
