package com.samosaai.masala.debug

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.llm.LlmConfig
import com.samosaai.masala.masala
import com.samosaai.masala.tools.Capability
import com.samosaai.masala.tools.ToolGate
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Debug builds only (plan Phase 1, lane B): send a prompt with every registered tool, see the raw
 * responses, the tool calls and their results. Tools that change something ask first, as Phase 2's
 * confirmation gate will.
 */
class DebugActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("debug_llm", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) { Surface(Modifier.fillMaxSize()) { DebugScreen() } }
        }
    }

    private fun loadConfig() = LlmConfig.default.let { d ->
        LlmConfig(
            prefs.getString("base_url", null) ?: d.baseUrl,
            prefs.getString("api_key", null) ?: d.apiKey,
            prefs.getString("model", null) ?: d.model,
        )
    }

    private fun saveConfig(c: LlmConfig) =
        prefs.edit().putString("base_url", c.baseUrl).putString("api_key", c.apiKey).putString("model", c.model).apply()

    /** A tool waiting for the user's answer. */
    private class Pending(val tool: String, val args: String, val answer: CompletableDeferred<Boolean>)

    @Composable
    private fun DebugScreen() {
        val scope = rememberCoroutineScope()
        var config by remember { mutableStateOf(loadConfig()) }
        var prompt by rememberSaveable { mutableStateOf("Set an alarm for 7:30 tomorrow called Standup") }
        var running by remember { mutableStateOf(false) }
        var pending by remember { mutableStateOf<Pending?>(null) }
        val events = remember { mutableStateListOf<DebugAgent.Event>() }
        val listState = rememberLazyListState()
        LaunchedEffect(events.size) { if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1) }

        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val gate = remember {
            ToolGate { tool, args ->
                val answer = CompletableDeferred<Boolean>()
                pending = Pending(tool.definition.name, args.toString(), answer)
                if (answer.await()) null else ToolResult.Failure("The user declined.")
            }
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.debug_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(config.baseUrl, { config = config.copy(baseUrl = it) }, label = { Text(stringResource(R.string.debug_base_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    config.apiKey, { config = config.copy(apiKey = it) },
                    label = { Text(stringResource(R.string.debug_api_key)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(config.model, { config = config.copy(model = it) }, label = { Text(stringResource(R.string.debug_model)) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.debug_prompt)) }, modifier = Modifier.fillMaxWidth().testTag("debug_prompt"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !running && config.isConfigured && prompt.isNotBlank(),
                    onClick = {
                        saveConfig(config)
                        events.clear()
                        running = true
                        scope.launch {
                            val app = masala
                            try {
                                DebugAgent(config.client(app), app.tools.tools, app.toolExecutor(gate)).run(prompt) { events += it }
                            } catch (e: Exception) {
                                events += DebugAgent.Event.Error("${e::class.simpleName}: ${e.message}")
                            } finally {
                                running = false
                            }
                        }
                    },
                ) { Text(stringResource(if (running) R.string.debug_running else R.string.debug_send)) }
                OutlinedButton(enabled = !running, onClick = {
                    saveConfig(config)
                    scope.launch {
                        events.clear()
                        config.client(masala).listModels()
                            .onSuccess { events += DebugAgent.Event.Answer(it.joinToString("\n")) }
                            .onFailure { events += DebugAgent.Event.Error("${it::class.simpleName}: ${it.message}") }
                    }
                }) { Text(stringResource(R.string.debug_models)) }
                OutlinedButton(onClick = {
                    events.clear()
                    events += DebugAgent.Event.Raw(masala.actionLog.tail(20).joinToString("\n").ifEmpty { "(empty)" })
                }) { Text(stringResource(R.string.debug_action_log)) }
            }
            Text(stringResource(R.string.debug_tools, masala.tools.tools.size), style = MaterialTheme.typography.bodySmall)

            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(events) { event ->
                    EventRow(event) { marker ->
                        val capability = Capability.byMarker(marker)
                        if (capability != null) {
                            capability.settingsIntent(this@DebugActivity)?.let(::startActivity)
                        } else if (marker.startsWith("android.permission.")) {
                            permission.launch(marker)
                        }
                    }
                }
            }
        }

        pending?.let { p ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.debug_confirm_title, p.tool)) },
                text = { Text(p.args, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                confirmButton = { TextButton(onClick = { pending = null; p.answer.complete(true) }) { Text(stringResource(R.string.debug_allow)) } },
                dismissButton = { TextButton(onClick = { pending = null; p.answer.complete(false) }) { Text(stringResource(R.string.debug_deny)) } },
            )
        }
    }

    @Composable
    private fun EventRow(event: DebugAgent.Event, onGrant: (String) -> Unit) {
        val (label, body) = when (event) {
            is DebugAgent.Event.Raw -> stringResource(R.string.debug_event_raw) to event.json
            is DebugAgent.Event.Call -> stringResource(R.string.debug_event_call, event.name) to event.args
            is DebugAgent.Event.Result -> stringResource(R.string.debug_event_result, event.name) to when (val r = event.result) {
                is ToolResult.Success -> "OK: ${r.output}"
                is ToolResult.Failure -> "FAIL: ${r.message}"
                is ToolResult.NeedsCapability -> "NEEDS ${r.capability}: ${r.reason}"
            }
            is DebugAgent.Event.Answer -> stringResource(R.string.debug_event_answer) to event.text
            is DebugAgent.Event.Error -> stringResource(R.string.debug_event_error) to event.message
        }
        // Raw responses are long; they start collapsed and a tap shows the whole thing.
        var expanded by remember(event) { mutableStateOf(false) }
        val raw = event is DebugAgent.Event.Raw
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .then(if (raw) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(8.dp)
                .testTag("debug_event"),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                body,
                fontFamily = if (raw || event is DebugAgent.Event.Call) FontFamily.Monospace else null,
                fontSize = if (raw) 11.sp else 14.sp,
                maxLines = if (raw && !expanded) 6 else Int.MAX_VALUE,
            )
            val needs = (event as? DebugAgent.Event.Result)?.result as? ToolResult.NeedsCapability
            if (needs != null) TextButton(onClick = { onGrant(needs.capability) }) { Text(stringResource(R.string.debug_grant)) }
        }
    }
}
