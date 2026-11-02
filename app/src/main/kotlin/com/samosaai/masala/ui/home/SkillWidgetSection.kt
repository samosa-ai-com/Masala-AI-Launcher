package com.samosaai.masala.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import com.samosaai.masala.skills.SkillScheduling
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.samosaai.masala.brief.BriefWidget
import com.samosaai.masala.core.brief.BriefSpeech
import com.samosaai.masala.core.contract.SkillOutput
import com.samosaai.masala.ui.components.Markdown

/** What the skill widget needs: the skill, and its newest run for the result line. */
interface SkillWidgetSource {
    fun skill(id: String): MasalaSkill?
    fun latest(skillId: String): Flow<SkillRunEntity?>

    /** When the skill's scheduled task runs next, or null. */
    fun nextRunAt(skillId: String): Long? = null

    /** The skill's default schedule, if the user hasn't been asked about it yet. */
    fun scheduleOffer(skillId: String): SkillScheduling.Offer? = null
    fun acceptSchedule(offer: SkillScheduling.Offer) = Unit
    fun declineSchedule(skillId: String) = Unit

    /** Reads [text] aloud; null when it played or was stopped, else why it couldn't. */
    suspend fun speak(text: String): String? = null
    fun stopSpeaking() = Unit
    fun speaking(): StateFlow<Boolean> = MutableStateFlow(false)

    /** Runs the skill's scheduled task now; null when it started, else why not. */
    suspend fun runNow(skillId: String): String? = null

    /** True once per run when it should be read aloud without a tap (plan `phase5-daily-brief.md` D5). */
    suspend fun claimAutoRead(run: SkillRunEntity): Boolean = false
}

/**
 * A skill's card on home (plan Phase 4 M2): icon and title, the latest result line or progress, and a
 * quick text field when the skill takes text, otherwise a button. Tapping opens the skill sheet.
 */
@Composable
fun SkillWidgetCard(
    section: SkillWidgetSection,
    source: SkillWidgetSource,
    onAction: (MasalaAction) -> Unit,
    /** The home gutter by default; a grid cell, already inset, passes none. */
    modifier: Modifier = Modifier.padding(horizontal = HOME_GUTTER),
) {
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
    // The default schedule is only set up after the user says yes.
    var offer by remember(skill.id) { mutableStateOf<SkillScheduling.Offer?>(null) }
    LaunchedEffect(skill.id) { offer = source.scheduleOffer(skill.id) }
    var nextRun by remember(skill.id, latest) { mutableStateOf(source.nextRunAt(skill.id)) }
    offer?.let { o ->
        AlertDialog(
            onDismissRequest = { source.declineSchedule(skill.id); offer = null },
            title = { Text(stringResource(R.string.skill_schedule_title, title)) },
            text = { Text(stringResource(R.string.skill_schedule_body, o.schedule.summary)) },
            confirmButton = { TextButton(onClick = { source.acceptSchedule(o); offer = null; nextRun = source.nextRunAt(skill.id) }) { Text(stringResource(R.string.skill_schedule_yes)) } },
            dismissButton = { TextButton(onClick = { source.declineSchedule(skill.id); offer = null }) { Text(stringResource(R.string.skill_schedule_no)) } },
        )
    }
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(com.samosaai.masala.ui.components.Radii.card),
        // The translucent container matches no theme colour, so the text colour has to be set.
        // Glass on the wallpaper (UI refresh U3), always light text whatever the scheme.
        colors = CardDefaults.cardColors(containerColor = com.samosaai.masala.ui.theme.MasalaTheme.tokens.wallpaperGlass, contentColor = com.samosaai.masala.ui.theme.MasalaColors.Text),
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f)),
    ) {
        Column(
            Modifier.clickable(role = Role.Button, onClick = open).padding(16.dp).heightIn(min = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SymbolDisc(SymbolIcons.icon(skill.widget?.icon ?: "star"), SymbolIcons.tint(skill.widget?.tint), if (section.size == SkillWidgetSize.SMALL) 32 else 40)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            if (section.size != SkillWidgetSize.SMALL && skill.schedule != null && skill.widget?.output == SkillOutput.MARKDOWN) {
                ScheduledResult(skill, section.size, latest, line, nextRun, source, open)
            } else if (section.size != SkillWidgetSize.SMALL) {
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
                nextRun?.let { Text(stringResource(R.string.skill_widget_next_run, formatNextRun(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (SkillInput.TEXT in skill.widget?.inputs.orEmpty()) {
                    QuickInput(title) { onAction(MasalaAction.RunSkill(skill.id, it)) }
                } else {
                    Button(onClick = open) { Text(stringResource(R.string.skill_widget_open)) }
                }
            }
        }
    }
}

/**
 * A scheduled skill's result (the daily brief): when it ran, the Markdown (the first section at medium
 * size), and Read aloud, Run now and Open. A fresh result may be read aloud once without a tap.
 */
@Composable
private fun ScheduledResult(
    skill: MasalaSkill,
    size: SkillWidgetSize,
    latest: SkillRunEntity?,
    answer: String?,
    nextRun: Long?,
    source: SkillWidgetSource,
    open: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaking by remember(source) { source.speaking() }.collectAsStateWithLifecycle()
    val done = latest?.takeIf { it.status == "done" }
    val markdown = done?.let { answer }?.trim()
    fun say(text: String) = scope.launch {
        source.speak(BriefSpeech.toSpeech(text))?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
    }
    // The first look at home after a fresh result, not only the moment it arrives.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(done?.id, markdown) {
        val run = done ?: return@LaunchedEffect
        val text = markdown?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (source.claimAutoRead(run)) say(text)
        }
    }
    when {
        latest?.status == "running" -> Text(stringResource(R.string.skill_widget_working), style = MaterialTheme.typography.bodyMedium)
        latest?.status == "failed" -> latest.error?.let { Text(it.trim(), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        markdown != null -> {
            val at = done.finishedAt ?: done.startedAt
            Text(
                BriefWidget.runLabel(at, stringResource(R.string.skill_widget_ran_today), stringResource(R.string.skill_widget_ran_yesterday)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Capped, so the buttons stay on screen; Open shows the whole result.
            Markdown(
                if (size == SkillWidgetSize.LARGE) BriefWidget.paragraphs(markdown) else BriefWidget.preview(markdown),
                // Measured at full height and clipped: a capped measure makes the paragraphs overlap.
                Modifier.heightIn(max = if (size == SkillWidgetSize.LARGE) 200.dp else 96.dp).clipToBounds()
                    .wrapContentHeight(Alignment.Top, unbounded = true),
                linkColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
    nextRun?.let { Text(stringResource(R.string.skill_widget_next_run, formatNextRun(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (markdown != null) {
            TextButton(onClick = { if (speaking) source.stopSpeaking() else say(markdown) }) {
                Text(stringResource(if (speaking) R.string.skill_widget_stop_reading else R.string.skill_widget_read_aloud))
            }
        }
        TextButton(enabled = latest?.status != "running", onClick = {
            scope.launch { source.runNow(skill.id)?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() } }
        }) { Text(stringResource(R.string.skill_widget_run_now)) }
        Button(onClick = open) { Text(stringResource(R.string.skill_widget_open)) }
    }
}

/** "07:30" today, "Thu 07:30" otherwise. */
internal fun formatNextRun(millis: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)
    return if (at.toLocalDate() == today) time else DateTimeFormatter.ofPattern("EEE").format(at) + " " + time
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
