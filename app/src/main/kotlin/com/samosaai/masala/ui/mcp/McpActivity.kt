package com.samosaai.masala.ui.mcp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.connectors.mcp.McpProbe
import com.samosaai.masala.connectors.mcp.McpServerConfig
import com.samosaai.masala.connectors.mcp.McpServerStore
import com.samosaai.masala.connectors.mcp.McpStrings
import com.samosaai.masala.connectors.mcp.formatHeaderLines
import com.samosaai.masala.connectors.mcp.parseHeaderLines
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.masala
import com.samosaai.masala.ui.settings.SettingsScaffold
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.launch

/** Settings → Connected tools (MCP): add, test, switch off and remove the user's own tool servers. */
class McpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) { Surface(Modifier.fillMaxSize()) { McpScreen() } }
        }
    }

    private val strings = McpStrings { id, args -> getString(id, *args) }

    @Composable
    private fun McpScreen() {
        val store = masala.mcp
        val servers by store.servers.collectAsStateWithLifecycle()
        var editing by remember { mutableStateOf<McpServerConfig?>(null) }
        var adding by rememberSaveable { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var message by remember { mutableStateOf<String?>(null) }

        BackHandler(enabled = adding || editing != null) { adding = false; editing = null }
        if (adding || editing != null) {
            ServerForm(store, editing, onDone = { adding = false; editing = null })
            return
        }
        SettingsScaffold(stringResource(R.string.mcp_title), onBack = { finish() }) {
            Button(onClick = { adding = true }) { Text(stringResource(R.string.mcp_add)) }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            if (servers.isEmpty()) Text(stringResource(R.string.mcp_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
            servers.forEach { server ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.mcp_server_line, McpServerStore.hostOf(server.url), server.tools.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = server.enabled, onCheckedChange = { store.setEnabled(server.id, it) })
                    }
                    server.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(onClick = { editing = server }) { Text(stringResource(R.string.mcp_edit)) }
                        TextButton(onClick = { scope.launch { message = store.refresh(server.id, strings) } }) { Text(stringResource(R.string.mcp_refresh)) }
                        TextButton(onClick = { store.remove(server.id) }) { Text(stringResource(R.string.mcp_remove)) }
                    }
                }
            }
        }
    }

    @Composable
    private fun ServerForm(store: McpServerStore, existing: McpServerConfig?, onDone: () -> Unit) {
        val scope = rememberCoroutineScope()
        var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
        var url by rememberSaveable { mutableStateOf(existing?.url.orEmpty()) }
        var token by rememberSaveable { mutableStateOf(existing?.token.orEmpty()) }
        var headers by rememberSaveable { mutableStateOf(existing?.headers?.let(::formatHeaderLines).orEmpty()) }
        var result by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }

        fun parsedHeaders() = parseHeaderLines(headers)

        SettingsScaffold(stringResource(R.string.mcp_title), onBack = onDone) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.mcp_name)) }, singleLine = true)
            OutlinedTextField(
                url, { url = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.mcp_url)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.mcp_token)) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(headers, { headers = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.mcp_headers)) }, minLines = 2)
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && url.isNotBlank(), onClick = {
                    val h = parsedHeaders()
                    if (h == null) { result = getString(R.string.mcp_error_bad_header); return@OutlinedButton }
                    busy = true
                    scope.launch {
                        result = when (val p = store.probe(url, token, h, strings)) {
                            is McpProbe.Ok -> getString(R.string.mcp_connected, p.server.name.ifBlank { McpServerStore.hostOf(url) }, p.tools.size)
                            is McpProbe.Failed -> p.message
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.mcp_test)) }
                Button(enabled = !busy && url.isNotBlank() && name.isNotBlank(), onClick = {
                    val h = parsedHeaders()
                    if (h == null) { result = getString(R.string.mcp_error_bad_header); return@Button }
                    busy = true
                    scope.launch {
                        val (saved, text) = store.save(existing?.id, name, url, token, h, strings)
                        busy = false
                        if (saved) onDone() else result = text
                    }
                }) { Text(stringResource(R.string.mcp_save)) }
            }
        }
    }
}
