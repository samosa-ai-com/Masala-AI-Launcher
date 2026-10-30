package com.samosaai.masala.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samosaai.masala.BuildConfig
import com.samosaai.masala.R
import com.samosaai.masala.ui.brand.MasalaWordmark
import com.samosaai.masala.ui.components.SectionCard
import com.samosaai.masala.ui.components.SettingsRow
import com.samosaai.masala.ui.theme.MasalaMono

/** What a licence file in `assets/licenses` covers, by its name. */
internal fun licenceTitle(file: String): String = when (file.substringBeforeLast('.')) {
    "OFL_inter" -> "Inter · SIL Open Font License 1.1"
    "OFL_space_grotesk" -> "Space Grotesk · SIL Open Font License 1.1"
    "OFL_figtree" -> "Figtree · SIL Open Font License 1.1"
    else -> file.substringBeforeLast('.').replace('_', ' ')
}

/** Settings → About (plan UI refresh U5): version and build, and the licences shipped in `assets/licenses`. */
@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val files = remember { context.assets.list("licenses").orEmpty().sorted() }
    var open by remember { mutableStateOf<String?>(null) }
    SettingsScaffold(stringResource(R.string.about_title), onBack) {
        MasalaWordmark(markSize = 48.dp)
        SectionCard {
            SettingsRow(Icons.Rounded.Info, stringResource(R.string.about_version), summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}")
        }
        SectionCard(title = stringResource(R.string.about_licences)) {
            files.forEach { f -> SettingsRow(Icons.Rounded.Description, licenceTitle(f), onClick = { open = f }) }
            SettingsRow(Icons.Rounded.Description, "compose-richtext · Apache License 2.0", summary = stringResource(R.string.about_richtext))
        }
    }
    open?.let { f ->
        val text = remember(f) { runCatching { context.assets.open("licenses/$f").bufferedReader().use { it.readText() } }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { open = null },
            confirmButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.about_close)) } },
            title = { Text(licenceTitle(f)) },
            text = { Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MasalaMono), modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        )
    }
}
