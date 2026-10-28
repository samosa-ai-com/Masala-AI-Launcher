package com.samosaai.masala.ui.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.masala
import com.samosaai.masala.ui.generate.GenerateActivity
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A saved version rendered full-screen over the current wallpaper, so the user sees how it
 * would look on their phone. Read-only: Apply makes it current, Refine opens it in Generate.
 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val versionId = intent.getLongExtra(EXTRA_VERSION_ID, -1)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(
                com.samosaai.masala.data.LauncherSettings()
            )
            MasalaTheme(settings) {
                PreviewScreen(versionId, onClose = ::finish)
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun PreviewScreen(versionId: Long, onClose: () -> Unit) {
        val app = masala
        val scope = rememberCoroutineScope()
        var layout by remember(versionId) { mutableStateOf<LayoutDoc?>(null) }
        var label by remember(versionId) { mutableStateOf("") }
        var failed by remember(versionId) { mutableStateOf(false) }
        LaunchedEffect(versionId) {
            val row = withContext(Dispatchers.IO) {
                runCatching {
                    app.layouts.historyState.value.versions.find { it.id == versionId }
                }.getOrNull()
            }
            label = row?.label.orEmpty()
            layout = withContext(Dispatchers.IO) { app.layouts.layoutFor(versionId) }
            failed = layout == null
        }

        // The window itself is wallpaper-backed (Theme.Masala.Home, like the real home
        // screen), so the layout draws over the user's actual wallpaper — live ones included.
        // My earlier WallpaperManager-bitmap attempt is gone: it could come back null and could
        // never match parallax anyway.
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // Scrimmed bars so controls stay readable over any wallpaper.
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_back)) }
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        maxLines = 1,
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.settings_version_preview_failed),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        layout == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        else -> LayoutPreview(layout!!, Modifier.fillMaxSize())
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val doc = layout
                    Button(enabled = doc != null, onClick = {
                        scope.launch {
                            app.layouts.select(versionId)
                            onClose()
                        }
                    }) { Text(stringResource(R.string.settings_version_apply)) }
                    OutlinedButton(onClick = {
                        startActivity(
                            Intent(this@PreviewActivity, GenerateActivity::class.java)
                                .putExtra(GenerateActivity.EXTRA_REVISE_VERSION_ID, versionId)
                        )
                    }) { Text(stringResource(R.string.settings_refine_version)) }
                }
            }
        }
    }

    companion object {
        const val EXTRA_VERSION_ID = "version_id"
    }
}
