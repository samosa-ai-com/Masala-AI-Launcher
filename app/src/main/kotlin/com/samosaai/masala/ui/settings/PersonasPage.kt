package com.samosaai.masala.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.Persona
import com.samosaai.masala.core.contract.SkillInput
import com.samosaai.masala.core.contract.SkillOutput
import com.samosaai.masala.core.contract.SkillWidget
import com.samosaai.masala.masala
import com.samosaai.masala.skills.PersonaOverride
import com.samosaai.masala.ui.theme.MasalaColors
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The experts Masala can call: added, edited and removed here. An expert is a consultant, so there
 * is nothing to chat to — only what it is told, when it is called, which tools it gets, and whether
 * it is on the list at all.
 */
@Composable
fun PersonasPage(onBack: () -> Unit, initialEditId: String? = null) {
    val app = LocalContext.current.masala
    var editing by remember { mutableStateOf<MasalaSkill?>(null) }
    var creating by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val personas = remember(version) { app.experts() }
    val hidden = remember(version) {
        app.personas.hiddenIds().mapNotNull { id -> app.skills.getSkillById(id) }
    }

    // Coming from Skills: open the expert that was tapped, not the list.
    LaunchedEffect(initialEditId, version) {
        if (editing == null && initialEditId != null) {
            editing = app.skills.getSkillById(initialEditId)?.takeIf { it.persona != null }
        }
    }

    val editingNow = editing
    if (editingNow != null) {
        val community = remember(editingNow.id) { app.skills.getCommunitySkills().any { it.id == editingNow.id } }
        PersonaEditor(
            skill = editingNow,
            onDone = { editing = null; version++ },
            onCancel = { editing = null },
            onRemove = {
                app.scope.launch {
                    if (community) app.skills.removeCommunity(editingNow.id)
                    else app.personas.setHidden(editingNow.id, true)
                    editing = null
                    version++
                }
            },
            removable = true,
        )
        return
    }

    if (creating) {
        NewExpertForm(
            onSaved = { creating = false; version++ },
            onCancel = { creating = false },
        )
        return
    }

    SettingsScaffold(stringResource(R.string.personas_title), onBack = onBack) {
        Text(
            stringResource(R.string.personas_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MasalaTheme.tokens.textMedium,
        )
        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.personas_add))
        }
        if (personas.isEmpty()) {
            Text(stringResource(R.string.personas_empty), style = MaterialTheme.typography.bodyMedium)
        }
        personas.forEach { skill ->
            val edited = remember(version, skill.id) { app.personas.override(skill.id) != null }
            Column(
                Modifier.fillMaxWidth()
                    .clickable { editing = skill }
                    .padding(vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(skill.title.ifBlank { skill.id }, style = MaterialTheme.typography.titleMedium)
                    if (edited) {
                        Text(
                            stringResource(R.string.personas_edited),
                            style = MaterialTheme.typography.labelSmall,
                            color = MasalaColors.Gold,
                            modifier = Modifier
                                .background(MasalaColors.Gold.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    skill.persona?.whenToUse?.ifBlank { skill.description }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasalaTheme.tokens.textMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider()
        }

        if (hidden.isNotEmpty()) {
            Text(
                stringResource(R.string.personas_hidden_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            hidden.forEach { skill ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(skill.title.ifBlank { skill.id }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        app.personas.setHidden(skill.id, false)
                        version++
                    }) { Text(stringResource(R.string.personas_restore)) }
                }
            }
        }
    }
}

/**
 * The expert's own instructions (prefilled with what shipped), when to call it, and its tools.
 * Saving with everything untouched removes the override, so a reset is just saving the shipped text.
 */
@Composable
private fun PersonaEditor(
    skill: MasalaSkill,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    removable: Boolean,
) {
    val app = LocalContext.current.masala
    val shipped = skill.persona?.systemPrompt.orEmpty()
    val shippedWhen = skill.persona?.whenToUse.orEmpty()
    val stored = remember(skill.id) { app.personas.override(skill.id) }
    var prompt by remember(skill.id) { mutableStateOf(stored?.systemPrompt ?: shipped) }
    var whenToUse by remember(skill.id) { mutableStateOf(stored?.whenToUse ?: shippedWhen) }
    // Null = every tool the consult scope allows; a set = exactly these.
    var limits by remember(skill.id) { mutableStateOf(stored?.allowTools != null) }
    var chosen by remember(skill.id) {
        mutableStateOf((stored?.allowTools ?: offerableTools(app)).toSet())
    }
    var confirmRemove by remember { mutableStateOf(false) }
    val tools = remember { offerableTools(app) }

    SettingsScaffold(stringResource(R.string.personas_edit_title, skill.title.ifBlank { skill.id }), onBack = onCancel) {
        // What the main agent reads when it decides whether to call this expert.
        Text(stringResource(R.string.personas_when_label), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            whenToUse,
            { whenToUse = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.personas_when_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MasalaTheme.tokens.textMedium,
        )

        Text(
            stringResource(R.string.personas_prompt_label),
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedTextField(
            prompt,
            { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 12,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { prompt = shipped; whenToUse = shippedWhen; limits = false; chosen = tools.toSet() }) {
            Text(stringResource(R.string.personas_reset))
        }

        SettingsToggleRow(stringResource(R.string.personas_limit_tools), limits) { on ->
            limits = on
            if (on && chosen.isEmpty()) chosen = tools.toSet()
        }
        if (limits) {
            Text(
                stringResource(R.string.personas_tools_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MasalaTheme.tokens.textMedium,
            )
            Column(Modifier.fillMaxWidth()) {
                tools.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { chosen = if (name in chosen) chosen - name else chosen + name }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = name in chosen, onCheckedChange = { on ->
                            chosen = if (on) chosen + name else chosen - name
                        })
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            app.tools.get(name)?.definition?.description?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MasalaTheme.tokens.textLow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                val text = prompt.trim().takeIf { it.isNotBlank() && it != shipped }
                val whenText = whenToUse.trim().takeIf { it.isNotBlank() && it != shippedWhen }
                val list = if (limits) chosen.toList() else null
                app.personas.save(skill.id, PersonaOverride(systemPrompt = text, allowTools = list, whenToUse = whenText))
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.personas_save)) }
        if (removable) {
            OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.personas_remove))
            }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_back))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.personas_remove_title, skill.title.ifBlank { skill.id })) },
            text = { Text(stringResource(R.string.personas_remove_message)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) {
                    Text(stringResource(R.string.personas_remove))
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_back)) } },
        )
    }
}

/** A new expert, saved as a community skill so the list, the prompt and consults all pick it up. */
@Composable
private fun NewExpertForm(onSaved: () -> Unit, onCancel: () -> Unit) {
    val app = LocalContext.current.masala
    var name by remember { mutableStateOf("") }
    var whenToUse by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && whenToUse.isNotBlank() && prompt.isNotBlank()

    SettingsScaffold(stringResource(R.string.personas_new_title), onBack = onCancel) {
        OutlinedTextField(
            name,
            { name = it },
            label = { Text(stringResource(R.string.personas_new_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.personas_when_label), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            whenToUse,
            { whenToUse = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.personas_prompt_label), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            prompt,
            { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 12,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Button(
            enabled = valid && !saving,
            onClick = {
                saving = true
                val id = "custom-" + UUID.randomUUID().toString().take(8)
                val skill = MasalaSkill(
                    id = id,
                    title = name.trim().take(40),
                    description = whenToUse.trim().take(140),
                    instructions = "Answer as this expert, in that role's voice and expertise. This is a consultation: " +
                        "answer the question, ask only what would change the answer, and do not use tools to change " +
                        "anything on the phone unless the user asks you to.",
                    platforms = listOf("masala"),
                    persona = Persona(systemPrompt = prompt.trim(), whenToUse = whenToUse.trim()),
                    widget = SkillWidget("psychology", "indigo", inputs = listOf(SkillInput.TEXT), output = SkillOutput.MARKDOWN),
                )
                app.scope.launch {
                    app.skills.saveCommunity(skill)
                    saving = false
                    if (app.skills.getSkillById(id) != null) onSaved() else onCancel()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.personas_new_save)) }
    }
}

/** Tools an expert can be given: never the consult tool itself, nor a skill sheet. */
private fun offerableTools(app: com.samosaai.masala.MasalaApp): List<String> =
    app.tools.tools.map { it.definition.name }.filterNot { it == "consult_expert" || it == "run_skill" }.sorted()
