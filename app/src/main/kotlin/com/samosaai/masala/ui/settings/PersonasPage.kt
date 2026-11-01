package com.samosaai.masala.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.samosaai.masala.masala
import com.samosaai.masala.skills.PersonaOverride
import com.samosaai.masala.ui.theme.MasalaColors
import com.samosaai.masala.ui.theme.MasalaTheme

/**
 * The experts Masala can consult, and the two things the user can change about each: what it is
 * told to be, and which tools it may use. Never a chat: there is no screen that talks to a persona.
 */
@Composable
fun PersonasPage(onBack: () -> Unit) {
    val app = LocalContext.current.masala
    var editing by remember { mutableStateOf<MasalaSkill?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val personas = remember(version) { app.skills.getPersonas() }

    val persona = editing
    if (persona != null) {
        PersonaEditor(persona, onDone = { editing = null; version++ }, onCancel = { editing = null })
        return
    }

    SettingsScaffold(stringResource(R.string.personas_title), onBack = onBack) {
        Text(
            stringResource(R.string.personas_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MasalaTheme.tokens.textMedium,
        )
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
                if (skill.description.isNotBlank()) {
                    Text(
                        skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MasalaTheme.tokens.textMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

/**
 * The expert's own instructions (prefilled with what shipped) and its tools. Saving with both
 * untouched removes the override, so a reset is just saving the shipped text back.
 */
@Composable
private fun PersonaEditor(skill: MasalaSkill, onDone: () -> Unit, onCancel: () -> Unit) {
    val app = LocalContext.current.masala
    val shipped = skill.persona?.systemPrompt.orEmpty()
    val stored = remember(skill.id) { app.personas.override(skill.id) }
    var prompt by remember(skill.id) { mutableStateOf(stored?.systemPrompt ?: shipped) }
    // Null = every tool the consult scope allows; a set = exactly these.
    var limits by remember(skill.id) { mutableStateOf(stored?.allowTools != null) }
    var chosen by remember(skill.id) {
        mutableStateOf((stored?.allowTools ?: offerableTools(app)).toSet())
    }
    val tools = remember { offerableTools(app) }

    SettingsScaffold(stringResource(R.string.personas_edit_title, skill.title.ifBlank { skill.id }), onBack = onCancel) {
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
        TextButton(onClick = { prompt = shipped; limits = false; chosen = tools.toSet() }) {
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
                val list = if (limits) chosen.toList() else null
                app.personas.save(skill.id, PersonaOverride(systemPrompt = text, allowTools = list))
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.personas_save)) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_back))
        }
    }
}

/** Tools an expert can be given: never the consult tool itself, nor a skill sheet. */
private fun offerableTools(app: com.samosaai.masala.MasalaApp): List<String> =
    app.tools.tools.map { it.definition.name }.filterNot { it == "consult_expert" || it == "run_skill" }.sorted()
