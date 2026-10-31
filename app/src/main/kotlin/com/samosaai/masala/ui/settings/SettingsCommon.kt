package com.samosaai.masala.ui.settings

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R


/** The frame every settings page shares: a titled top bar with Back and a scrolling column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(title: String, onBack: () -> Unit, close: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    // UI refresh U5: a round arrow (or, on the settings home, a close button) instead of a text button.
    val back: @Composable () -> Unit = {
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(horizontal = 8.dp).size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
        ) {
            Icon(
                if (close) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                stringResource(if (close) R.string.settings_close else R.string.action_back),
            )
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = if (close) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall) },
                navigationIcon = { if (!close) back() },
                actions = { if (close) back() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SettingsSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** A row that opens a sub-page, with a chevron. */
@Composable
fun SettingsNavRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
    }
}

/** One search field for every settings list (skills, mini apps, scheduled tasks). */
@Composable
fun ListSearchField(value: String, onValue: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value, onValue,
        label = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A label and a switch. The whole row toggles, so the touch target is the full width. */
@Composable
fun SettingsToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // The row handles the tap; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}
