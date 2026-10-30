package com.samosaai.masala.ui.drawer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samosaai.masala.R
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.GlobeLayout
import com.samosaai.masala.core.apps.UsageRange
import com.samosaai.masala.launcher.IconCache
import com.samosaai.masala.launcher.rememberAppIcon
import com.samosaai.masala.ui.theme.LocalMotion
import com.samosaai.masala.ui.theme.MasalaTheme

/** One app on the globe: its entry, how often it was opened, and its circle in design units. */
data class GlobeApp(val entry: AppEntry, val count: Int, val circle: GlobeLayout.Circle)

/** Packs the ranked apps ([GlobeLayout]); the most used first. */
fun globeApps(ranked: List<Pair<AppEntry, Int>>): List<GlobeApp> {
    val circles = GlobeLayout.layout(ranked.map { it.second })
    return ranked.mapIndexed { i, (e, c) -> GlobeApp(e, c, circles[i]) }
}

/**
 * The globe (plan UI refresh U3, globe board): the most-used apps as circles sized and placed by how
 * often they're opened in [range], over a slowly turning wireframe globe. Circles spring to new places
 * when ranks change; typing dims apps that don't match; labels only inside the bigger circles. TalkBack
 * reads the apps in usage order. The globe turns only while the drawer is [open] and motion is allowed.
 */
@Composable
fun Globe(
    apps: List<GlobeApp>,
    icons: IconCache,
    query: String,
    open: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MasalaTheme.tokens
    val turning = open && !LocalMotion.current.reduced
    val turn = rememberInfiniteTransition(label = "globe")
    val phase by if (turning) {
        turn.animateFloat(1f, -1f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Reverse), label = "meridians")
    } else {
        turn.animateFloat(1f, 1f, infiniteRepeatable(tween(1_000)), label = "still")
    }
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f).semantics { isTraversalGroup = true }) {
        val unit = maxWidth / 370f
        Canvas(Modifier.matchParentSize()) {
            val k = size.width / 370f
            scale(k, pivot = Offset.Zero) {
                val c = Offset(185f, 185f)
                val line = tokens.accent
                drawOval(line.copy(alpha = 0.10f), Offset(3f, 125f), Size(364f, 120f), style = Stroke(1f / k))
                drawOval(line.copy(alpha = 0.08f), Offset(3f, 65f), Size(364f, 240f), style = Stroke(1f / k))
                drawCircle(line.copy(alpha = 0.10f), 182f, c, style = Stroke(1f / k))
                // Meridians: ellipses whose width swings through zero, so the globe seems to turn.
                for (m in listOf(0.2f, 0.45f, 0.7f, 0.95f)) {
                    val w = 182f * kotlin.math.sin((m + phase * 0.5f) * Math.PI.toFloat()).let { kotlin.math.abs(it) }
                    drawOval(line.copy(alpha = 0.08f), Offset(185f - w, 3f), Size(2 * w, 364f), style = Stroke(1f / k))
                }
            }
        }
        val q = query.trim().lowercase()
        apps.forEachIndexed { index, app ->
            key(app.entry.key.id) {
                val target = app.circle
                val spring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.8f, stiffness = 120f)
                val x by animateFloatAsState(target.x.toFloat(), spring, label = "x")
                val y by animateFloatAsState(target.y.toFloat(), spring, label = "y")
                val r by animateFloatAsState(target.r.toFloat(), spring, label = "r")
                val lit = q.isEmpty() || app.entry.label.lowercase().contains(q)
                val dim by animateFloatAsState(if (lit) 1f else 0.16f, tween(250), label = "dim")
                val d = r * 2
                val labelled = d >= 62f
                val top = index == 0
                val icon = rememberAppIcon(icons, app.entry.key)
                Column(
                    Modifier
                        .offset(unit * (x - r), unit * (y - r))
                        .size(unit * d)
                        .alpha(dim)
                        .clip(CircleShape)
                        .background(Color(0xE0242527))
                        .border(if (top) 1.5.dp else 1.dp, if (top) tokens.accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.10f), CircleShape)
                        .combinedClickable(role = Role.Button, onClick = { onLaunch(app.entry) }, onLongClick = { onMenu(app.entry) })
                        .semantics { contentDescription = app.entry.label; traversalIndex = index.toFloat() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val iconSize = (d * if (labelled) 0.40f else 0.56f).coerceIn(14f, 40f)
                    if (icon != null) Image(icon, null, Modifier.size(unit * iconSize))
                    if (labelled) {
                        Text(app.entry.label, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MasalaTheme.tokens.textHigh, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
            }
        }
    }
}

/** Today · 7 days · 30 days. */
@Composable
fun RangeSwitch(range: UsageRange, onRange: (UsageRange) -> Unit) {
    val tokens = MasalaTheme.tokens
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(UsageRange.TODAY to R.string.globe_today, UsageRange.WEEK to R.string.globe_week, UsageRange.MONTH to R.string.globe_month).forEach { (r, label) ->
            val on = r == range
            TextButton(
                onClick = { onRange(r) },
                modifier = Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(if (on) tokens.textHigh else Color.Transparent).semantics { selected = on },
            ) { Text(stringResource(label), color = if (on) MaterialTheme.colorScheme.surface else tokens.textMedium, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** First time the globe opens without Usage access (decision 11): ask once, then a one-line hint. */
@Composable
fun UsageAccessCard(asked: Boolean, onAllow: () -> Unit, onSkip: () -> Unit) {
    val tokens = MasalaTheme.tokens
    if (asked) {
        Text(stringResource(R.string.globe_own_counts), style = MaterialTheme.typography.bodySmall, color = tokens.textMedium, modifier = Modifier.padding(horizontal = 20.dp))
        return
    }
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape).background(tokens.accent.copy(alpha = 0.10f)).border(1.dp, tokens.accent.copy(alpha = 0.3f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.usage_access_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.usage_access_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.usage_access_allow), color = tokens.accentText) }
            TextButton(onClick = onSkip) { Text(stringResource(R.string.usage_access_skip), color = tokens.textMedium) }
        }
    }
}

@Composable
internal fun GlobeCaption() {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.globe_caption), style = MaterialTheme.typography.bodySmall, color = MasalaTheme.tokens.textMedium)
    }
}
