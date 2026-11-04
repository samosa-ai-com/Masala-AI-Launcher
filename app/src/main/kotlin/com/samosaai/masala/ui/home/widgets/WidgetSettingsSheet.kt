package com.samosaai.masala.ui.home.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.ComponentPrefs
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetPrefs
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.contract.prefsKey
import com.samosaai.masala.core.contract.resolved
import com.samosaai.masala.widgets.WidgetPrefsStore
import com.samosaai.masala.widgets.WidgetStateStore

/**
 * One widget's own settings: the goal of a
 * counter, a timer's minutes and sound, a checklist's reset, a web value's URL, a camera's source.
 * Stored per placement, so editing one Water widget leaves another's goal alone, and a template
 * stays the template. Reached from the widget's long-press menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsSheet(
    spec: WidgetSpec,
    prefs: WidgetPrefs,
    store: WidgetStateStore,
    /** Stream sources for a camera tile, id to label. */
    sources: List<Pair<String, String>> = emptyList(),
    onOpenPage: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var draft by remember(spec.id) { mutableStateOf(prefs) }
    fun save(next: WidgetPrefs) {
        draft = next
        WidgetPrefsStore.save(store, spec.id, next)
    }
    fun set(path: String, change: (ComponentPrefs) -> ComponentPrefs) =
        save(draft.copy(components = draft.components + (path to change(draft.components[path] ?: ComponentPrefs()))))

    val shown = remember(draft, spec) { spec.resolved(draft) }
    // Every component, nested ones included, with the path its settings live under.
    val entries = remember(shown, draft) {
        val list = mutableListOf<Pair<String, WidgetComponent>>()
        var index = 0
        fun walk(c: WidgetComponent) {
            list += prefsKey(c, index++) to c
            if (c is WidgetComponent.Row) c.children.forEach(::walk)
            if (c is WidgetComponent.Column) c.children.forEach(::walk)
        }
        shown.components.forEach(::walk)
        list
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                spec.title ?: spec.id,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item("title") {
                    SettingText(
                        label = stringResource(R.string.widget_settings_title),
                        value = draft.title ?: "",
                        placeholder = spec.title ?: spec.id,
                        onValue = { text -> save(draft.copy(title = text.ifBlank { null })) },
                    )
                }
                items(entries, key = { it.first }) { (path, component) ->
                    val current = draft.components[path] ?: ComponentPrefs()
                    HorizontalDivider()
                    when (component) {
                        is WidgetComponent.Counter -> {
                            SectionTitle(stringResource(R.string.widget_settings_counter))
                            SettingText(stringResource(R.string.widget_settings_label), current.label ?: component.label, null) { v -> set(path) { it.copy(label = v.ifBlank { null }) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberSetting(stringResource(R.string.widget_settings_goal), current.goal ?: component.goal, Modifier.weight(1f)) { v -> set(path) { it.copy(goal = v) } }
                                NumberSetting(stringResource(R.string.widget_settings_step), current.step ?: component.step, Modifier.weight(1f)) { v -> set(path) { it.copy(step = v) } }
                            }
                            SettingText(
                                stringResource(R.string.widget_settings_date),
                                current.date ?: component.date ?: "",
                                "2026-12-31",
                            ) { v -> set(path) { it.copy(date = v.ifBlank { null }) } }
                            ResetChips(current.reset ?: component.reset ?: "never", listOf("never" to R.string.widget_settings_never, "daily" to R.string.widget_settings_daily)) { r -> set(path) { it.copy(reset = r) } }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.widget_settings_monthly_repeat), modifier = Modifier.weight(1f))
                                Switch(checked = (current.repeat ?: component.repeat) == "monthly", onCheckedChange = { on ->
                                    set(path) { it.copy(repeat = if (on) "monthly" else "never") }
                                })
                            }
                        }
                        is WidgetComponent.Checklist -> {
                            SectionTitle(stringResource(R.string.widget_settings_checklist))
                            ResetChips(current.reset ?: component.reset ?: "never", listOf("never" to R.string.widget_settings_never, "daily" to R.string.widget_settings_daily, "weekly" to R.string.widget_settings_weekly)) { r -> set(path) { it.copy(reset = r) } }
                            TextButton(onClick = {
                                // Start a fresh list now; the tick pattern is this key's value.
                                store.set(spec.id, component.key, component.items.joinToString(",") { "false" })
                            }) { Text(stringResource(R.string.widget_settings_reset_now)) }
                        }
                        is WidgetComponent.Timer -> {
                            SectionTitle(stringResource(R.string.widget_settings_timer))
                            SettingText(stringResource(R.string.widget_settings_label), current.label ?: component.label, null) { v -> set(path) { it.copy(label = v.ifBlank { null }) } }
                            NumberSetting(stringResource(R.string.widget_settings_minutes), (current.seconds ?: component.seconds) / 60, Modifier.fillMaxWidth()) { v ->
                                if (v != null && v > 0) set(path) { it.copy(seconds = v * 60) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.widget_settings_sound), modifier = Modifier.weight(1f))
                                Switch(checked = current.sound ?: component.sound, onCheckedChange = { on -> set(path) { it.copy(sound = on) } })
                            }
                        }
                        is WidgetComponent.Progress -> {
                            SectionTitle(stringResource(R.string.widget_settings_progress))
                            SettingText(stringResource(R.string.widget_settings_label), current.label ?: component.label, null) { v -> set(path) { it.copy(label = v.ifBlank { null }) } }
                            NumberSetting(stringResource(R.string.widget_settings_max), (current.max ?: component.max).toInt(), Modifier.fillMaxWidth()) { v -> if (v != null && v > 0) set(path) { it.copy(max = v.toDouble()) } }
                            ResetChips(current.reset ?: component.reset ?: "never", listOf("never" to R.string.widget_settings_never, "monthly" to R.string.widget_settings_monthly)) { r -> set(path) { it.copy(reset = r) } }
                        }
                        is WidgetComponent.Gauge -> {
                            SectionTitle(stringResource(R.string.widget_settings_gauge))
                            SettingText(stringResource(R.string.widget_settings_label), current.label ?: component.label, null) { v -> set(path) { it.copy(label = v.ifBlank { null }) } }
                            NumberSetting(stringResource(R.string.widget_settings_max), (current.max ?: component.max).toInt(), Modifier.fillMaxWidth()) { v -> if (v != null && v > 0) set(path) { it.copy(max = v.toDouble()) } }
                        }
                        is WidgetComponent.Globe -> {
                            SectionTitle(stringResource(R.string.widget_settings_globe))
                            SettingText(stringResource(R.string.widget_settings_label), current.label ?: component.label.orEmpty(), null) { v -> set(path) { it.copy(label = v.ifBlank { null }) } }
                        }
                        is WidgetComponent.WebValue -> {
                            SectionTitle(stringResource(R.string.widget_settings_web))
                            SettingText(stringResource(R.string.widget_settings_label), current.label ?: component.label, null) { v -> set(path) { it.copy(label = v.ifBlank { null }) } }
                            SettingText(stringResource(R.string.widget_settings_url), current.httpUrl ?: component.http.url, "https://…") { v -> set(path) { it.copy(httpUrl = v.ifBlank { null }) } }
                            SettingText(stringResource(R.string.widget_settings_json_path), current.jsonPath ?: component.http.jsonPath, "$.price") { v -> set(path) { it.copy(jsonPath = v.ifBlank { null }) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberSetting(stringResource(R.string.widget_settings_minutes), (current.every ?: component.http.every) / 60, Modifier.weight(1f)) { v -> if (v != null && v > 0) set(path) { it.copy(every = v * 60) } }
                                SettingText(stringResource(R.string.widget_settings_suffix), current.suffix ?: component.suffix.orEmpty(), "€", Modifier.weight(1f)) { v -> set(path) { it.copy(suffix = v.ifBlank { null }) } }
                            }
                        }
                        is WidgetComponent.Video, is WidgetComponent.Snapshot -> {
                            SectionTitle(stringResource(R.string.widget_settings_camera))
                            val selected = current.sourceId
                                ?: if (component is WidgetComponent.Video) component.stream.sourceId else (component as WidgetComponent.Snapshot).sourceId
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                sources.forEach { (id, label) ->
                                    FilterChip(selected = selected == id, onClick = { set(path) { it.copy(sourceId = id) } }, label = { Text(label) })
                                }
                            }
                            TextButton(onClick = { onOpenPage("WIDGETS") }) { Text(stringResource(R.string.widget_settings_add_camera)) }
                        }
                        else -> Unit
                    }
                }
                item("done") {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.widgets_save)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SettingText(
    label: String,
    value: String,
    placeholder: String?,
    modifier: Modifier = Modifier,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberSetting(label: String, value: Int?, modifier: Modifier = Modifier, onValue: (Int?) -> Unit) {
    // The field owns its text while typing: clearing it must not snap back to the stored value,
    // and only a complete number updates the widget.
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(9)
            text = digits
            digits.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ResetChips(current: String?, options: List<Pair<String?, Int>>, onPick: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = current == value, onClick = { onPick(value) }, label = { Text(stringResource(label)) })
        }
    }
}
