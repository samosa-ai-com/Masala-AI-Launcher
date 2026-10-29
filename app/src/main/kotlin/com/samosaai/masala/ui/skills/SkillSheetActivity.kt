package com.samosaai.masala.ui.skills

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.SkillInput
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.masala
import com.samosaai.masala.skills.Attachment
import com.samosaai.masala.skills.SkillMessage
import com.samosaai.masala.skills.SkillRunEvent
import com.samosaai.masala.skills.SkillRunState
import com.samosaai.masala.ui.components.rememberCapabilityGrant
import com.samosaai.masala.ui.home.ConfirmationSheet
import com.samosaai.masala.ui.home.SymbolDisc
import com.samosaai.masala.ui.home.SymbolIcons
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.secondbrain.ProposalCards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A skill's conversation (plan Phase 4 M2): starters when empty, the chat, result cards, an input row
 * with the skill's input buttons. Opens the skill's latest run unless it is given a prompt (a new run)
 * or a run id. Runs live in `SkillRunner`, so they go on when this closes.
 */
class SkillSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val skillId = intent.getStringExtra(EXTRA_SKILL_ID)
        val skill = skillId?.let { masala.skills.getSkillById(it) }
        if (skill == null) {
            Toast.makeText(this, R.string.skill_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) {
                Surface(Modifier.fillMaxSize()) {
                    SkillSheet(skill, intent.getStringExtra(EXTRA_PROMPT), intent.getLongExtra(EXTRA_RUN_ID, -1L).takeIf { it >= 0 })
                    // This activity can run the agent, so it has to show the confirmation question too.
                    ConfirmationSheet(masala.confirmationGate)
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SkillSheet(skill: MasalaSkill, initialPrompt: String?, initialRunId: Long?) {
        val app = masala
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var run by remember { mutableStateOf<StateFlow<SkillRunState>?>(null) }
        // Survives rotation and process death, so the intent's prompt doesn't start a second run.
        var runId by rememberSaveable { mutableStateOf<Long?>(null) }
        var loaded by remember { mutableStateOf(false) }
        var text by rememberSaveable { mutableStateOf("") }
        val attachments = remember { mutableStateListOf<Attachment>() }
        var reporting by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf<SkillRunEvent.Result?>(null) }

        suspend fun open(id: Long) {
            run = app.skillRunner.open(id)
            runId = id.takeIf { run != null }
        }

        suspend fun begin(message: com.samosaai.masala.llm.ChatMessage) {
            open(app.skillRunner.start(skill.id, message) ?: return)
        }

        fun send(prompt: String) {
            val picked = attachments.toList()
            text = ""
            attachments.clear()
            scope.launch {
                // Decoding and scaling a photo is too slow for the main thread.
                val message = withContext(Dispatchers.IO) { SkillMessage.build(prompt, picked, SkillInputs::photoBase64) }
                val current = run?.value
                if (current == null) begin(message) else app.skillRunner.send(current.runId, message)
            }
        }

        val retryPrompt = stringResource(R.string.capability_retry_prompt)
        // A dialog that was allowed carries on by itself; a settings screen needs Try again.
        val grant = rememberCapabilityGrant { granted -> if (granted) send(retryPrompt) }

        LaunchedEffect(Unit) {
            val saved = runId
            when {
                saved != null -> open(saved)
                initialRunId != null -> open(initialRunId)
                initialPrompt != null -> begin(SkillMessage.build(initialPrompt, emptyList(), SkillInputs::photoBase64))
                else -> app.database.skillRuns().latestFor(skill.id).first()?.let { open(it.id) }
            }
            loaded = true
        }

        val state by (run ?: remember { kotlinx.coroutines.flow.MutableStateFlow(SkillRunState(0, skill.id)) }).collectAsStateWithLifecycle()
        val listState = rememberLazyListState()
        LaunchedEffect(state.events.size) { if (state.events.isNotEmpty()) listState.animateScrollToItem(state.events.size - 1) }

        val pickDir = File(app.tools.fileAccess.outputDir(), "picked")
        fun picked(kind: SkillInput): (android.net.Uri?) -> Unit = { uri ->
            if (uri != null) scope.launch {
                // A picked video can be large; copy it off the main thread.
                val attachment = withContext(Dispatchers.IO) { SkillInputs.copyPicked(context, uri, kind, pickDir) }
                if (attachment != null) attachments.add(attachment) else Toast.makeText(context, R.string.input_pick_failed, Toast.LENGTH_SHORT).show()
            }
        }
        val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), picked(SkillInput.PHOTO))
        val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), picked(SkillInput.VIDEO))
        val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), picked(SkillInput.DOCUMENT))
        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), picked(SkillInput.AUDIO_FILE))
        val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val result = saving
            saving = null
            if (uri != null && result != null) {
                val ok = ResultFiles.saveTo(context, result.ref, uri)
                Toast.makeText(context, if (ok) R.string.result_saved else R.string.result_save_failed, Toast.LENGTH_SHORT).show()
            }
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SymbolDisc(SymbolIcons.glyph(skill.widget?.icon ?: "star"), SymbolIcons.tint(skill.widget?.tint), 40)
                Column(Modifier.weight(1f)) {
                    Text(skill.title.ifBlank { skill.id }, style = MaterialTheme.typography.titleLarge)
                    skill.persona?.disclaimer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (run != null) TextButton(onClick = { run = null; runId = null; text = ""; attachments.clear() }) { Text(stringResource(R.string.skill_new_chat)) }
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (loaded && state.events.isEmpty()) {
                    item {
                        val starters = skill.persona?.starters.orEmpty()
                        if (starters.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                starters.forEach { starter -> AssistChip(onClick = { send(starter) }, label = { Text(starter) }) }
                            }
                        } else {
                            Text(
                                skill.description.ifBlank { stringResource(R.string.skill_empty_hint) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(state.events) { event ->
                    when (event) {
                        is SkillRunEvent.Message -> MessageBubble(event) { reporting = it }
                        is SkillRunEvent.ToolStep -> Text(
                            stringResource(
                                when (event.done) {
                                    null -> R.string.skill_step_running
                                    true -> R.string.skill_step_done
                                    false -> R.string.skill_step_failed
                                },
                                event.name,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is SkillRunEvent.Progress -> Text(event.label + "…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is SkillRunEvent.Result -> ResultCard(event) { result -> saving = result; save.launch(File(result.ref).name) }
                        is SkillRunEvent.NeedsCapability -> Column {
                            Text(event.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            Row {
                                TextButton(onClick = { grant(event.capability) }) { Text(stringResource(R.string.command_grant)) }
                                TextButton(enabled = !state.running, onClick = { send(retryPrompt) }) { Text(stringResource(R.string.command_try_again)) }
                            }
                        }
                        is SkillRunEvent.Failed -> Text(event.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        SkillRunEvent.Done -> Unit
                    }
                }
            }

            ProposalCards(app, Modifier.padding(horizontal = 16.dp))

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val inputs = skill.widget?.inputs.orEmpty().filter { it != SkillInput.TEXT }
                if (inputs.isNotEmpty() || attachments.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        attachments.toList().forEach { a -> AssistChip(onClick = { attachments.remove(a) }, label = { Text("✕ ${a.name}", maxLines = 1) }) }
                        inputs.forEach { input ->
                            val enabled = input in SkillInputs.supported
                            AssistChip(
                                enabled = enabled,
                                onClick = {
                                    when (input) {
                                        SkillInput.PHOTO -> pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        SkillInput.VIDEO -> pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                                        SkillInput.DOCUMENT -> pickDocument.launch(arrayOf("*/*"))
                                        SkillInput.AUDIO_FILE -> pickAudio.launch(arrayOf("audio/*"))
                                        else -> Unit
                                    }
                                },
                                label = { Text(stringResource(inputLabel(input)) + if (enabled) "" else " · " + stringResource(R.string.input_later)) },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.skill_input_hint)) }, maxLines = 4)
                    if (state.running) {
                        Button(onClick = { app.skillRunner.stop(state.runId) }) { Text(stringResource(R.string.skill_stop)) }
                    } else {
                        Button(enabled = text.isNotBlank() || attachments.isNotEmpty(), onClick = { send(text) }) { Text(stringResource(R.string.skill_send)) }
                    }
                }
            }
        }

        reporting?.let { reported ->
            ReportDialog(
                onReport = { reasons, note ->
                    app.aiReports.add(skill.id, reasons, note, reported)
                    reporting = null
                    Toast.makeText(context, R.string.report_thanks, Toast.LENGTH_SHORT).show()
                },
                onDismiss = { reporting = null },
            )
        }
    }

    private fun inputLabel(input: SkillInput) = when (input) {
        SkillInput.TEXT -> R.string.input_text
        SkillInput.PHOTO -> R.string.input_photo
        SkillInput.VIDEO -> R.string.input_video
        SkillInput.AUDIO_FILE -> R.string.input_audio_file
        SkillInput.DOCUMENT -> R.string.input_document
        SkillInput.FOLDER -> R.string.input_folder
        SkillInput.PATH -> R.string.input_path
        SkillInput.RECORD_AUDIO -> R.string.input_record
    }

    companion object {
        const val EXTRA_SKILL_ID = "skillId"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_RUN_ID = "runId"

        fun intent(context: Context, skillId: String, prompt: String? = null, runId: Long? = null) =
            Intent(context, SkillSheetActivity::class.java)
                .putExtra(EXTRA_SKILL_ID, skillId)
                .apply {
                    prompt?.let { putExtra(EXTRA_PROMPT, it) }
                    runId?.let { putExtra(EXTRA_RUN_ID, it) }
                }
    }
}
