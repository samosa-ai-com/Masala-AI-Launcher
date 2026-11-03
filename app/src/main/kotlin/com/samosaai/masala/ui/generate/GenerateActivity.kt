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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.layout.GenerateResult
import com.samosaai.masala.layout.LayoutGenerator
import com.samosaai.masala.layout.LlmChatCaller
import com.samosaai.masala.layout.generateRequest
import com.samosaai.masala.llm.LlmConfig
import com.samosaai.masala.masala
import com.samosaai.masala.ui.settings.LayoutPreview
import com.samosaai.masala.ui.settings.SettingsScaffold
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI home screens (plan Phase 3, lane C): a prompt (and optional photo) → generated layout →
 * rendered preview → apply as a labeled version, or undo. A failed or rejected generation never
 * touches the current layout.
 */
class GenerateActivity : ComponentActivity() {
    /** The chat model from Settings → Masala AI → Models & keys. */
    private fun loadConfig() = LlmConfig.default

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
        var imageFormat by remember { mutableStateOf("png") }
        var photoName by remember { mutableStateOf<String?>(null) }
        var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
        var status by remember { mutableStateOf<String?>(null) }
        var appliedLabel by remember { mutableStateOf<String?>(null) }
        val apps by app.launcher.apps.collectAsStateWithLifecycle()
        val homeLayout by app.layouts.base.collectAsStateWithLifecycle()
        val previewLayout = (phase as? Phase.Preview)?.layout

        // Opened from a saved version: locked in refine mode for that layout.
        // Opened fresh: the user picks New vs Refine with the segmented control.
        val reviseVersionId = intent.getLongExtra(EXTRA_REVISE_VERSION_ID, -1)
        val lockedRefine = reviseVersionId >= 0
        var refineMode by rememberSaveable(lockedRefine) { mutableStateOf(lockedRefine) }
        val reviseLabel = remember(reviseVersionId) {
            if (lockedRefine) app.layouts.historyState.value.versions.find { it.id == reviseVersionId }?.label else null
        }
        var refineDoc by remember { mutableStateOf<LayoutDoc?>(null) }

        // Opened from a saved version: load it as the preview so Refine edits that layout.
        LaunchedEffect(reviseVersionId) {
            if (reviseVersionId >= 0 && phase is Phase.Idle) {
                val layout = app.layouts.layoutFor(reviseVersionId)
                if (layout == null) {
                    status = getString(R.string.generate_version_gone)
                } else {
                    refineDoc = layout
                    phase = Phase.Preview(layout)
                }
            }
        }

        /** The layout a refine run edits: the just-made preview, else the revise target, else home. */
        val reviseBase: LayoutDoc? = if (refineMode) previewLayout ?: refineDoc ?: homeLayout else null

        fun toast(text: String) = Toast.makeText(this@GenerateActivity, text, Toast.LENGTH_SHORT).show()

        val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val prepared = runCatching { preparePhoto(uri) }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (prepared == null) {
                        toast(getString(R.string.generate_photo_failed))
                    } else {
                        imageBase64 = prepared.first
                        imageFormat = prepared.second
                        photoName = uri.lastPathSegment
                    }
                }
            }
        }

        fun run(regenerate: Boolean) {
            scope.launch {
                phase = Phase.Generating
                status = null
                appliedLabel = null
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
                        imageFormat = imageFormat,
                        temperature = if (regenerate) REGENERATE_TEMPERATURE else null,
                        revise = if (refineMode) reviseBase else null,
                    )
                    when (val result = LayoutGenerator(caller).generate(request)) {
                        is GenerateResult.Generated -> Phase.Preview(result.layout)
                        is GenerateResult.Rejected -> Phase.Rejected(result.problems.map { it.toString() })
                        is GenerateResult.Failed -> Phase.Failed(
                            getString(R.string.generate_failed) + "\n" + result.message
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Phase.Failed("${e::class.simpleName}: ${e.message}")
                }
            }
        }

        val generating = phase is Phase.Generating
        SettingsScaffold(stringResource(R.string.generate_title), onBack = ::finish) {
            // The mode is the whole point of the screen, so it comes first and the single
            // primary button below always says what it will do. No separate Generate + Refine
            // buttons to misread.
            if (!lockedRefine) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !refineMode,
                        onClick = { refineMode = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.generate_mode_new)) }
                    SegmentedButton(
                        selected = refineMode,
                        onClick = { refineMode = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.generate_mode_refine)) }
                }
            }
            if (refineMode) {
                Text(
                    if (reviseLabel != null) stringResource(R.string.generate_refining, reviseLabel)
                    else stringResource(R.string.generate_refining_home),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            OutlinedTextField(
                prompt,
                {
                    prompt = it
                    if (!generating && phase !is Phase.Idle) phase = Phase.Idle
                },
                label = { Text(stringResource(R.string.generate_prompt_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (refineMode) R.string.generate_refine_hint
                            else R.string.generate_prompt_hint
                        )
                    )
                },
                maxLines = 3,
                enabled = !generating,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !generating, onClick = { pickPhoto.launch("image/*") }) {
                    Text(stringResource(if (photoName == null) R.string.generate_pick_photo else R.string.generate_photo_picked))
                }
                if (imageBase64 != null) {
                    TextButton(enabled = !generating, onClick = { imageBase64 = null; photoName = null }) {
                        Text(stringResource(R.string.generate_photo_remove))
                    }
                }
            }
            Button(
                enabled = !generating && apps.isNotEmpty() && (prompt.isNotBlank() || imageBase64 != null) &&
                    (!refineMode || reviseBase != null),
                onClick = { run(regenerate = false) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (refineMode) R.string.generate_refine else R.string.generate_button
                    )
                )
            }

            when (val p = phase) {
                Phase.Idle -> {}
                Phase.Generating -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.generate_running), style = MaterialTheme.typography.bodyMedium)
                }
                is Phase.Preview -> PreviewPane(
                    layout = p.layout,
                    canApply = appliedLabel == null,
                    canUndo = appliedLabel != null,
                    onApply = {
                        scope.launch {
                            val labelPrompt = prompt.ifBlank { photoName ?: "Photo" }.take(40)
                            val problems = app.layouts.apply(getString(R.string.generate_version_label, labelPrompt), p.layout)
                            if (problems.isEmpty()) {
                                appliedLabel = labelPrompt
                                status = getString(R.string.generate_applied)
                            } else {
                                status = problems.first().toString()
                            }
                        }
                    },
                    onRegenerate = { run(regenerate = true) },
                    onUndo = {
                        scope.launch {
                            if (app.layouts.undo()) {
                                appliedLabel = null
                                status = getString(R.string.generate_undone)
                            }
                        }
                    },
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
    private fun ColumnScope.PreviewPane(
        layout: LayoutDoc,
        canApply: Boolean,
        canUndo: Boolean,
        onApply: () -> Unit,
        onRegenerate: () -> Unit,
        onUndo: () -> Unit
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LayoutPreview(layout, Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = canApply, onClick = onApply) { Text(stringResource(R.string.generate_apply)) }
            OutlinedButton(onClick = onRegenerate) { Text(stringResource(R.string.generate_regenerate)) }
            OutlinedButton(enabled = canUndo, onClick = onUndo) { Text(stringResource(R.string.generate_undo)) }
        }
    }

    companion object {
        /** Open the screen with this saved version loaded as the preview, ready to refine. */
        const val EXTRA_REVISE_VERSION_ID = "revise_version_id"

        const val REGENERATE_TEMPERATURE = 0.2f

        /** Longest side of a photo sent to the model; keeps the request small and fast. */
        const val MAX_PHOTO_SIDE_PX = 1568

        /** Re-encode budget; a two-step quality ladder fits almost any photo under it. */
        const val MAX_PHOTO_BYTES = 1_500_000
    }

    /**
     * Downscales to [MAX_PHOTO_SIDE_PX] and re-encodes as JPEG (quality 85, then 70), so the
     * data URI is small and the format always matches what is sent. Null when unreadable or
     * still over budget.
     */
    private fun preparePhoto(uri: android.net.Uri): Pair<String, String>? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_SIDE_PX) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        return try {
            var quality = 85
            var bytes = compressJpeg(bitmap, quality)
            if (bytes.size > MAX_PHOTO_BYTES) {
                quality = 70
                bytes = compressJpeg(bitmap, quality)
            }
            if (bytes.size > MAX_PHOTO_BYTES) null else Base64.encodeToString(bytes, Base64.NO_WRAP) to "jpeg"
        } finally {
            bitmap.recycle()
        }
    }

    private fun compressJpeg(bitmap: android.graphics.Bitmap, quality: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
