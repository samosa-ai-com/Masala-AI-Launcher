package com.samosaai.masala.ui.profile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.masala
import com.samosaai.masala.ui.settings.SettingsScaffold
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.launch

/**
 * Settings → Profile: the facts Masala remembers about the user. Saved into
 * the Second Brain's user card; the agent reads it from there.
 */
class ProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings) { Surface(Modifier.fillMaxSize()) { ProfileScreen() } }
        }
    }

    @Composable
    private fun ProfileScreen() {
        val app = masala
        val scope = rememberCoroutineScope()
        // Null = not loaded yet: loading fills them once. Typed text survives
        // rotation because null never comes back.
        var name by rememberSaveable { mutableStateOf<String?>(null) }
        var location by rememberSaveable { mutableStateOf<String?>(null) }
        var occupation by rememberSaveable { mutableStateOf<String?>(null) }
        var background by rememberSaveable { mutableStateOf<String?>(null) }
        if (name == null && location == null && occupation == null && background == null) {
            LaunchedEffect(Unit) {
                val current = app.secondBrain.profile()
                name = current["name"].orEmpty()
                location = current["location"].orEmpty()
                occupation = current["occupation"].orEmpty()
                background = current["background"].orEmpty()
            }
        }

        SettingsScaffold(stringResource(R.string.settings_profile), onBack = { finish() }) {
            Text(
                stringResource(R.string.settings_profile_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(name.orEmpty(), { name = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.secondbrain_profile_name)) }, singleLine = true)
            OutlinedTextField(location.orEmpty(), { location = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.secondbrain_profile_location)) }, singleLine = true)
            OutlinedTextField(occupation.orEmpty(), { occupation = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.secondbrain_profile_occupation)) }, singleLine = true)
            OutlinedTextField(background.orEmpty(), { background = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.secondbrain_profile_background)) }, minLines = 2)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        app.secondBrain.syncUser(
                            mapOf(
                                "name" to name.orEmpty(), "location" to location.orEmpty(),
                                "occupation" to occupation.orEmpty(), "background" to background.orEmpty(),
                            ),
                        )
                        finish()
                    }
                }) { Text(stringResource(R.string.secondbrain_profile_save)) }
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}
