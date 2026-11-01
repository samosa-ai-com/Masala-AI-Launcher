package com.samosaai.masala.ui.drawer

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.layout.IconSpec
import com.samosaai.masala.core.layout.KnownSymbols
import com.samosaai.masala.launcher.AppIconTile
import com.samosaai.masala.launcher.IconCache
import com.samosaai.masala.ui.home.SymbolDisc
import com.samosaai.masala.ui.home.SymbolIcons
import com.samosaai.masala.ui.theme.MasalaTheme

/**
 * Choosing an app's icon: one of the built-in symbols, or a small inline SVG pasted in. Reset puts the
 * app's own icon back. Either way the choice is stored on the app (IconSpec), not on a layout slot, so
 * it follows the app onto home, into a folder and into the drawer.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IconPickerSheet(
    entry: AppEntry,
    icons: IconCache,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var svg by remember(entry.key) { mutableStateOf("") }
    var refused by remember(entry.key) { mutableStateOf(false) }
    val tokens = MasalaTheme.tokens
    val shape = RoundedCornerShape(16.dp)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(shape)) { AppIconTile(icons, entry.key, entry.label, 48.dp) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.icon_picker_title), style = MaterialTheme.typography.titleLarge)
                    Text(entry.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onPick(null) }, enabled = entry.icon != null) {
                    Text(stringResource(R.string.icon_picker_reset))
                }
            }

            Text(stringResource(R.string.icon_picker_symbols), style = MaterialTheme.typography.labelLarge)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KnownSymbols.all.sorted().forEach { name ->
                    val label = name.replace('_', ' ')
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(shape)
                            .border(1.dp, tokens.hairline, shape)
                            .clickable(role = Role.Button) { onPick(IconSpec.SYMBOL_PREFIX + name) }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) { SymbolDisc(SymbolIcons.icon(name), null, 32) }
                }
            }

            HorizontalDivider(color = tokens.hairline)

            Text(stringResource(R.string.icon_picker_custom), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = svg,
                onValueChange = { svg = it.take(IconSpec.MAX_CHARS + 1); refused = false },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                label = { Text("<svg …</svg>") },
            )
            if (refused) {
                Text(
                    stringResource(R.string.icon_picker_bad_svg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(
                onClick = {
                    val spec = IconSpec.sanitize(svg)
                    if (spec == null) refused = true else onPick(spec)
                },
                enabled = svg.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.icon_picker_use_svg), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
        }
    }
}
