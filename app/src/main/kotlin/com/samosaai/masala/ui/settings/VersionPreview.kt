package com.samosaai.masala.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.layout.LayoutResolver
import com.samosaai.masala.masala
import com.samosaai.masala.ui.home.HomeScreen
import com.samosaai.masala.ui.home.RenderContext

/**
 * A read-only render of one saved version (or one [LayoutDoc]), shared by the settings version
 * list and the Generate screen so preview logic lives in one place. Locked, window insets off,
 * every tap inert.
 */
@Composable
fun VersionPreviewById(versionId: Long, modifier: Modifier = Modifier) {
    val app = androidx.compose.ui.platform.LocalContext.current.masala
    var layout by remember(versionId) { mutableStateOf<LayoutDoc?>(null) }
    var failed by remember(versionId) { mutableStateOf(false) }
    LaunchedEffect(versionId) {
        failed = false
        layout = app.layouts.layoutFor(versionId) ?: run { failed = true; null }
    }
    val doc = layout
    when {
        failed || (doc == null && failed) -> Text(
            stringResource(R.string.settings_version_preview_failed),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        doc == null -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> LayoutPreview(doc, modifier)
    }
}

@Composable
fun LayoutPreview(layout: LayoutDoc, modifier: Modifier = Modifier) {
    val app = androidx.compose.ui.platform.LocalContext.current.masala
    val snapshot by app.contextService.snapshot.collectAsStateWithLifecycle()
    val apps by app.launcher.apps.collectAsStateWithLifecycle()
    val resolved = remember(layout, snapshot) { LayoutResolver.resolve(layout, emptyList(), snapshot) }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .clip(MaterialTheme.shapes.medium),
    ) {
        HomeScreen(
            layout = resolved,
            render = RenderContext(
                icons = app.icons,
                apps = apps,
                now = snapshot.now,
                locked = true,
                onAction = {},
                onAppMenu = {},
                onClockClick = {},
            ),
            pinnedShortcuts = emptyList(),
            isDefault = true,
            onSetDefault = {},
            onSwipeUp = {},
            onSwipeDown = {},
            onOpenSettings = {},
            onLaunchShortcut = {},
            onUnpinShortcut = {},
            applyWindowInsets = false,
        )
    }
}
