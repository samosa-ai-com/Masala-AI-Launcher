package com.samosaai.masala.launcher

import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.masala
import com.samosaai.masala.ui.theme.MasalaTheme

/**
 * Another app's "Add to home screen" (KISS §2.20): Android sends `CONFIRM_PIN_SHORTCUT` to the
 * default home app, which asks the user and accepts. The system then keeps the shortcut pinned for
 * Masala, and the home screen lists it. Widget requests are declined until widgets are hosted.
 */
class PinShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = launcherApps.getPinItemRequest(intent)
        val shortcut = request?.shortcutInfo
        if (request == null || request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || shortcut == null) {
            finish()
            return
        }
        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(shortcut.`package`, 0)).toString()
        }.getOrDefault(shortcut.`package`)
        val label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.id).toString()

        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text(stringResource(R.string.pin_shortcut_title)) },
                    text = { Text(stringResource(R.string.pin_shortcut_body, appLabel, label)) },
                    confirmButton = {
                        TextButton(onClick = {
                            if (request.isValid) request.accept()
                            masala.launcher.refreshShortcuts()
                            finish()
                        }) { Text(stringResource(R.string.pin_shortcut_add)) }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.action_cancel)) } },
                )
            }
        }
    }
}
