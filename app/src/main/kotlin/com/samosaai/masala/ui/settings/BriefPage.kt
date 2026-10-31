package com.samosaai.masala.ui.settings

import android.Manifest
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.brief.BriefInputs
import com.samosaai.masala.core.brief.BriefTopics
import com.samosaai.masala.core.scheduled.ScheduledTask
import com.samosaai.masala.core.weather.Place
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.masala
import com.samosaai.masala.scheduled.ScheduledTaskScheduler
import com.samosaai.masala.skills.SkillScheduling
import com.samosaai.masala.ui.components.SectionCard
import com.samosaai.masala.ui.components.SettingsRow
import com.samosaai.masala.weather.WeatherClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Settings → Automation → Daily brief (plan `phase5-daily-brief.md` B6): when it runs, the place for the
 * weather (typed, or the phone's coarse location while the user is here), the followed topics (a note in
 * the Second Brain), and the ready notification.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BriefPage(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.masala
    val scope = rememberCoroutineScope()
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    var task by remember { mutableStateOf<ScheduledTask?>(null) }
    var topics by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        task = withContext(Dispatchers.IO) { app.skillScheduling.taskFor(BriefInputs.SKILL_ID) }
        topics = app.secondBrain.readPath(BriefTopics.PATH)?.text?.let(BriefTopics::parse).orEmpty()
    }

    fun setTime(at: LocalTime) = scope.launch {
        val skill = app.skills.getSkillById(BriefInputs.SKILL_ID) ?: return@launch
        task = withContext(Dispatchers.IO) {
            app.skillScheduling.setDaily(skill, at, SkillScheduling.now(), System.currentTimeMillis(), ZoneId.systemDefault())
                .also { ScheduledTaskScheduler.reschedule(app) }
        }
    }

    fun setOn(on: Boolean) = scope.launch {
        val skill = app.skills.getSkillById(BriefInputs.SKILL_ID) ?: return@launch
        withContext(Dispatchers.IO) {
            app.skillScheduling.setEnabled(skill, on, SkillScheduling.now(), System.currentTimeMillis(), ZoneId.systemDefault())
                ?.also { task = it; ScheduledTaskScheduler.reschedule(app) }
        }
    }

    fun saveTopics(next: List<String>) = scope.launch {
        val outcome = app.secondBrain.write(BriefTopics.PATH, BriefTopics.render(next, LocalDate.now()))
        if (outcome.ok) topics = next.distinctBy { it.lowercase() } else toast(outcome.errors.joinToString(" "))
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) return@rememberLauncherForActivityResult
        scope.launch {
            val place = WeatherClient.currentPlace(context, context.getString(R.string.brief_place_here))
            if (place == null) toast(context.getString(R.string.brief_place_no_fix)) else update { it.copy(briefPlace = place) }
        }
    }

    SettingsScaffold(stringResource(R.string.brief_title), onBack) {
        Text(stringResource(R.string.brief_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionCard(title = stringResource(R.string.brief_when)) {
            val time = task?.takeIf { it.enabled }?.let { runCatching { LocalDateTime.parse(it.startLocal).toLocalTime() }.getOrNull() }
            SettingsRow(
                Icons.Rounded.Schedule,
                stringResource(R.string.brief_time),
                summary = time?.let { stringResource(R.string.brief_time_every_day, "%02d:%02d".format(it.hour, it.minute)) } ?: stringResource(R.string.brief_time_off),
                onClick = {
                    val start = time ?: LocalTime.of(7, 30)
                    TimePickerDialog(context, { _, h, m -> setTime(LocalTime.of(h, m)) }, start.hour, start.minute, true).show()
                },
            )
            Row(Modifier.padding(horizontal = 16.dp)) {
                SettingsToggleRow(stringResource(R.string.brief_on), task?.enabled == true) { on -> setOn(on) }
            }
            Row(Modifier.padding(horizontal = 16.dp)) {
                SettingsToggleRow(stringResource(R.string.brief_notify), settings.briefNotify) { on -> update { it.copy(briefNotify = on) } }
            }
            Text(
                stringResource(R.string.brief_read_aloud_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        SectionCard(title = stringResource(R.string.brief_weather)) {
            SettingsRow(Icons.Rounded.Place, stringResource(R.string.brief_place), summary = settings.briefPlace?.label ?: stringResource(R.string.brief_place_none))
            PlaceSearch(onPick = { place -> update { it.copy(briefPlace = place) } }, onError = ::toast)
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                    Icon(Icons.Rounded.LocationOn, null)
                    Text(stringResource(R.string.brief_place_use_location), Modifier.padding(start = 4.dp))
                }
                if (settings.briefPlace != null) TextButton(onClick = { update { it.copy(briefPlace = null) } }) { Text(stringResource(R.string.brief_place_clear)) }
            }
        }

        SectionCard(title = stringResource(R.string.brief_topics)) {
            Text(
                stringResource(R.string.brief_topics_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                topics.forEach { topic ->
                    InputChip(
                        selected = false,
                        onClick = { saveTopics(topics - topic) },
                        label = { Text(topic) },
                        trailingIcon = { Icon(Icons.Rounded.Close, stringResource(R.string.brief_topic_remove, topic)) },
                    )
                }
            }
            var draft by rememberSaveable { mutableStateOf("") }
            val full = topics.size >= BriefTopics.MAX
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    draft, { draft = it },
                    Modifier.weight(1f),
                    enabled = !full,
                    singleLine = true,
                    placeholder = { Text(stringResource(if (full) R.string.brief_topics_full else R.string.brief_topic_hint)) },
                )
                TextButton(enabled = draft.isNotBlank() && !full, onClick = { saveTopics(topics + draft.trim()); draft = "" }) {
                    Text(stringResource(R.string.brief_topic_add))
                }
            }
        }
    }
}

/** A place name field: Search shows the matches, a tap picks one. */
@Composable
private fun PlaceSearch(onPick: (Place) -> Unit, onError: (String) -> Unit) {
    val app = LocalContext.current.masala
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Place>()) }
    var searching by remember { mutableStateOf(false) }
    val noMatch = stringResource(R.string.brief_place_no_match)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.brief_place_hint)) })
        TextButton(enabled = query.isNotBlank() && !searching, onClick = {
            searching = true
            scope.launch {
                app.weather.search(query).fold(
                    { found -> results = found; if (found.isEmpty()) onError(noMatch) },
                    { onError(it.message.orEmpty()) },
                )
                searching = false
            }
        }) { Text(stringResource(R.string.brief_place_search)) }
    }
    results.forEach { place ->
        SettingsRow(Icons.Rounded.Place, place.label, onClick = { onPick(place); results = emptyList(); query = "" })
    }
}
