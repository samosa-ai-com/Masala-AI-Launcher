package com.samosaai.masala.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.samosaai.masala.R

/**
 * Empty-space long-press on home (plan `docs/plans/wallpaper-generation.md`): Change
 * wallpaper (the shared catalog), Add widget (the sheet as before), Settings.
 */
@Composable
fun HomeLongPressMenu(
    onWallpaper: () -> Unit,
    onWidgets: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 4.dp) {
            Column(Modifier.padding(vertical = 8.dp)) {
                MenuRow(stringResource(R.string.home_menu_wallpaper), onWallpaper)
                MenuRow(stringResource(R.string.home_menu_widgets), onWidgets)
                MenuRow(stringResource(R.string.home_menu_settings), onSettings)
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
}
