package com.samosaai.masala.ui.generate

import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.layout.LayoutResolver
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.layout.GenerateResult
import com.samosaai.masala.layout.LayoutGenerator
import com.samosaai.masala.layout.LlmChatCaller
import com.samosaai.masala.layout.generateRequest
import com.samosaai.masala.llm.LlmConfig
import com.samosaai.masala.masala
import com.samosaai.masala.ui.home.HomeScreen
import com.samosaai.masala.ui.home.RenderContext
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI home screens (plan Phase 3, lane C): a prompt (and optional photo) → generated layout →
 * rendered preview → apply as a labeled version, or undo. A failed or rejected generation never
 * touches the current layout.
 */
class GenerateActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("debug_llm", Context.MODE_PRIVATE) }

    private fun loadConfig() = LlmConfig.default.let { d ->
        LlmConfig(
            prefs.getString("base_url", null) ?: d.baseUrl,
            prefs.getString("api_key", null) ?: d.apiKey,
            prefs.getString("model", null) ?: d.model,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) { Surface(Modifier.fillMaxSize()) { GenerateScreen() } }
        }
    }

    private sealed interface Phase {
        data object Idle : Phase
        data object Generating : Phase
        data class Preview(val layout: LayoutDoc) : Phase
        data class Rejected(val problems: List<String>) : Phase
        data class Failed(val message: String) : Phase
    }

    @Composable
    private fun GenerateScreen() {
        val app = masala
        val scope = rememberCoroutineScope()
        var prompt by rememberSaveable { mutableStateOf("") }
        var imageBase64 by remember { mutableStateOf<String?>(null) }
        var photoName by remember { mutableStateOf<String?>(null) }
        var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
        var status by remember { mutableStateOf<String?>(null) }
        val history by app.layouts.historyState.collectAsStateWithLifecycle()

        fun toast(text: String) = Toast.makeText(this@GenerateActivity, text, Toast.LENGTH_SHORT).show()

        val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (bytes == null) {
                        toast(getString(R.string.generate_photo_failed))
                    } else if (bytes.size > MAX_PHOTO_BYTES) {
                        toast(getString(R.string.generate_photo_too_big))
                    } else {
                        imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        photoName = uri.lastPathSegment
                    }
                }
            }
        }

        fun run(regenerate: Boolean) {
            scope.launch {
                phase = Phase.Generating
                status = null
                val config = loadConfig()
                if (!config.isConfigured) {
                    phase = Phase.Failed(getString(R.string.generate_not_configured))
                    return@launch
                }
                phase = try {
                    val caller = LlmChatCaller(config.client(this@GenerateActivity))
                    val request = app.generateRequest(
                        prompt,
                        imageBase64 = imageBase64,
                        temperature = if (regenerate) REGENERATE_TEMPERATURE else null,
                    )
                    when (val result = LayoutGenerator(caller).generate(request)) {
                        is GenerateResult.Generated -> Phase.Preview(result.layout)
                        is GenerateResult.Rejected -> Phase.Rejected(result.problems.map { it.toString() })
                        is GenerateResult.Failed -> Phase.Failed(
                            getString(R.string.generate_failed) + "\n" + result.message
                        )
                    }
                } catch (e: Exception) {
                    Phase.Failed("${e::class.simpleName}: ${e.message}")
                }
            }
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.generate_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                prompt, { prompt = it; if (phase !is Phase.Idle) phase = Phase.Idle },
                label = { Text(stringResource(R.string.generate_prompt_label)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickPhoto.launch("image/*") }) {
                    Text(stringResource(if (photoName == null) R.string.generate_pick_photo else R.string.generate_photo_picked))
                }
                if (imageBase64 != null) {
                    TextButton(onClick = { imageBase64 = null; photoName = null }) { Text(stringResource(R.string.generate_photo_remove)) }
                }
            }
            Button(
                enabled = phase !is Phase.Generating && (prompt.isNotBlank() || imageBase64 != null),
                onClick = { run(regenerate = false) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.generate_button)) }

            when (val p = phase) {
                Phase.Idle -> {}
                Phase.Generating -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.generate_running), style = MaterialTheme.typography.bodyMedium)
                }
                is Phase.Preview -> PreviewPane(
                    layout = p.layout,
                    canUndo = history.canUndo,
                    onApply = {
                        scope.launch {
                            val problems = app.layouts.apply(getString(R.string.generate_version_label, prompt.take(40)), p.layout)
                            status = if (problems.isEmpty()) getString(R.string.generate_applied) else problems.first().toString()
                        }
                    },
                    onRegenerate = { run(regenerate = true) },
                    onUndo = { scope.launch { app.layouts.undo() } },
                )
                is Phase.Rejected -> {
                    Text(stringResource(R.string.generate_rejected_title), style = MaterialTheme.typography.titleMedium)
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(p.problems) { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                is Phase.Failed -> {
                    Text(p.message, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { run(regenerate = false) }) { Text(stringResource(R.string.generate_retry)) }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    @Composable
    private fun PreviewPane(layout: LayoutDoc, canUndo: Boolean, onApply: () -> Unit, onRegenerate: () -> Unit, onUndo: () -> Unit) {
        val app = masala
        val snapshot by app.contextService.snapshot.collectAsStateWithLifecycle()
        val apps by app.launcher.apps.collectAsStateWithLifecycle()
        val resolved = remember(layout, snapshot) { LayoutResolver.resolve(layout, emptyList(), snapshot) }
        fun toast(text: String) = Toast.makeText(this@GenerateActivity, text, Toast.LENGTH_SHORT).show()

        HomeScreen(
            layout = resolved,
            render = RenderContext(
                icons = app.icons,
                apps = apps,
                now = snapshot.now,
                locked = true,
                onAction = { toast(getString(R.string.generate_preview_only)) },
                onAppMenu = {},
                onClockClick = {},
            ),
            pinnedShortcuts = emptyList(),
            isDefault = true,
            onSetDefault = {},
            onSwipeUp = {},
            onSwipeDown = {},
            onOpenSettings = { toast(getString(R.string.generate_preview_only)) },
            onLaunchShortcut = {},
            onUnpinShortcut = {},
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onApply) { Text(stringResource(R.string.generate_apply)) }
            OutlinedButton(onClick = onRegenerate) { Text(stringResource(R.string.generate_regenerate)) }
            OutlinedButton(enabled = canUndo, onClick = onUndo) { Text(stringResource(R.string.generate_undo)) }
        }
    }

    private companion object {
        const val MAX_PHOTO_BYTES = 4 * 1024 * 1024
        const val REGENERATE_TEMPERATURE = 0.2f
    }
}
