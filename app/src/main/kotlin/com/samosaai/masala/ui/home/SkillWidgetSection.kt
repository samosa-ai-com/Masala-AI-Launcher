package com.samosaai.masala.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.SkillInput
import com.samosaai.masala.core.contract.SkillWidgetSection
import com.samosaai.masala.core.contract.SkillWidgetSize
import com.samosaai.masala.data.SkillRunEntity
import com.samosaai.masala.skills.SkillRunner
import kotlinx.coroutines.flow.Flow

/** What the skill widget needs: the skill, and its newest run for the result line. */
interface SkillWidgetSource {
    fun skill(id: String): MasalaSkill?
    fun latest(skillId: String): Flow<SkillRunEntity?>
}

/**
 * A skill's card on home (plan Phase 4 M2): icon and title, the latest result line or progress, and a
 * quick text field when the skill takes text, otherwise a button. Tapping opens the skill sheet.
 */
@Composable
fun SkillWidgetCard(section: SkillWidgetSection, source: SkillWidgetSource, onAction: (MasalaAction) -> Unit) {
    val skill = remember(section.skillId) { source.skill(section.skillId) }
    if (skill == null) {
        Placeholder(section.skillId, stringResource(R.string.home_skill_widget_placeholder), null)
        return
    }
    val flow = remember(section.skillId) { source.latest(section.skillId) }
    val latest by flow.collectAsStateWithLifecycle(null)
    val line = remember(latest) {
        latest?.let { run ->
            when (run.status) {
                "running" -> null
                "failed" -> run.error
                else -> SkillRunner.lastAnswer(run.transcriptJson)
            }
        }
    }
    val title = skill.title.ifBlank { skill.id }
    val open = { onAction(MasalaAction.RunSkill(skill.id)) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        // The translucent container matches no theme colour, so the text colour has to be set.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Column(
            Modifier.clickable(role = Role.Button, onClick = open).padding(16.dp).heightIn(min = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SymbolDisc(SymbolIcons.glyph(skill.widget?.icon ?: "star"), SymbolIcons.tint(skill.widget?.tint), if (section.size == SkillWidgetSize.SMALL) 32 else 40)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            if (section.size != SkillWidgetSize.SMALL) {
                val text = when {
                    latest?.status == "running" -> stringResource(R.string.skill_widget_working)
                    else -> line
                }
                text?.let {
                    Text(
                        it.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (section.size == SkillWidgetSize.LARGE) 8 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (SkillInput.TEXT in skill.widget?.inputs.orEmpty()) {
                    QuickInput(title) { onAction(MasalaAction.RunSkill(skill.id, it)) }
                } else {
                    Button(onClick = open) { Text(stringResource(R.string.skill_widget_open)) }
                }
            }
        }
    }
}

@Composable
private fun QuickInput(title: String, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            text,
            { text = it },
            Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.skill_widget_hint, title)) },
            singleLine = true,
        )
        TextButton(enabled = text.isNotBlank(), onClick = { onSend(text.trim()); text = "" }) { Text(stringResource(R.string.skill_send)) }
    }
}
