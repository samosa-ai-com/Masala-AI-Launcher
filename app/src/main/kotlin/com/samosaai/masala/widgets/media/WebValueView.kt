package com.samosaai.masala.widgets.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.Align
import com.samosaai.masala.core.contract.Emphasis
import com.samosaai.masala.core.contract.HttpBinding
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetStyle
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.theme.OnWallpaper
import com.samosaai.masala.widgets.HttpValueStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * A web value on home (plan `docs/plans/widget-maker.md` §5): the fetched string plus [suffix],
 * or an em dash when nothing arrived yet; a stale value stays on screen with its age. Polls while
 * visible at [HttpBinding.every] (checker floor: 30 s); background refresh is the worker's job.
 * Nothing fetches unless [allowed]: the host needs the user's approval first.
 */
@Composable
fun WebValueView(
    component: WidgetComponent.WebValue,
    widgetId: String,
    suffix: String?,
    store: HttpValueStore,
    style: WidgetStyle?,
    modifier: Modifier = Modifier,
    allowed: Boolean = true,
) {
    val http: HttpBinding = component.http
    val entries by store.entries.collectAsState()
    LaunchedEffect(widgetId, http.url, http.jsonPath, allowed) {
        while (isActive) {
            if (allowed) store.refresh(widgetId, http)
            delay((http.every.coerceAtLeast(30) * 1000L))
        }
    }
    val entry = entries[store.keyFor(widgetId, http)]
    Column(modifier.fillMaxWidth()) {
        Text(
            component.label,
            style = MaterialTheme.typography.labelLarge,
            color = MasalaTheme.tokens.onWallpaperMedium,
            textAlign = alignOf(style),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            (entry?.value ?: "—") + (suffix?.let { " $it" }.orEmpty()),
            style = if (style?.emphasis == Emphasis.LARGE) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            color = OnWallpaper,
            textAlign = alignOf(style),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        entry?.let {
            val ageMin = (System.currentTimeMillis() - it.fetchedAt) / 60_000
            if (ageMin >= 2) {
                Text(
                    "· $ageMin min ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = MasalaTheme.tokens.onWallpaperMedium,
                    textAlign = alignOf(style),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!allowed) {
            Text(
                stringResource(R.string.widgets_needs_approval),
                style = MaterialTheme.typography.labelSmall,
                color = MasalaTheme.tokens.onWallpaperMedium,
                textAlign = alignOf(style),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun alignOf(style: WidgetStyle?): TextAlign = when (style?.align) {
    Align.CENTER -> TextAlign.Center
    Align.END -> TextAlign.End
    else -> TextAlign.Start
}
