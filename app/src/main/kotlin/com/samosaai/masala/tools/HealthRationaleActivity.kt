package com.samosaai.masala.tools

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
 * Why Masala reads health data. Health Connect opens it from its permission screen, and without one
 * declared the permission request fails without an error (arch §4).
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text(stringResource(R.string.health_rationale_title)) },
                    text = { Text(stringResource(R.string.health_rationale_body)) },
                    confirmButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.action_close)) } },
                )
            }
        }
    }
}
