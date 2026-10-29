package com.samosaai.masala.ui.secondbrain

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.R
import com.samosaai.masala.secondbrain.BrainGraph
import com.samosaai.masala.secondbrain.SecondBrainSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The memory as a graph (plan `docs/plans/second-brain-port.md`, P3b): nodes laid out
 * by a force-directed pass off the main thread, edges as curves, tap a node to open
 * the file. Pinch to zoom, drag to pan.
 */
@Composable
fun SecondBrainGraph(app: MasalaApp, snapshot: SecondBrainSnapshot?, onOpenFile: (String) -> Unit) {
    var prepared by remember { mutableStateOf<Pair<BrainGraph, List<Pair<Float, Float>>>?>(null) }
    LaunchedEffect(snapshot) {
        prepared = withContext(Dispatchers.Default) { app.secondBrain.preparedGraph() }
    }
    val ready = prepared
    if (ready == null) {
        Text(stringResource(R.string.secondbrain_graph_loading), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val (graph, points) = ready
    if (graph.nodes.isEmpty()) return
    var scale by remember(graph) { mutableStateOf(1f) }
    var pan by remember(graph) { mutableStateOf(Offset.Zero) }
    var selected by remember(graph) { mutableStateOf<Int?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val colors = MaterialTheme.colorScheme
    val palette = mapOf(
        "soul" to colors.primary,
        "map" to colors.secondary,
        "user" to colors.tertiary,
        "area" to colors.primaryContainer,
        "person" to colors.secondaryContainer,
        "note" to colors.tertiaryContainer,
        "raw" to colors.outline,
    )
    Column {
        Text(stringResource(R.string.secondbrain_graph), style = MaterialTheme.typography.labelLarge)
        // Node lookup once per vault, not once per edge per frame.
        val indexById = remember(graph) { graph.nodes.indices.associateBy({ graph.nodes[it].id }, { it }) }
        val edgePath = remember { Path() }
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceVariant)
                .onSizeChanged { viewSize = it }
                .pointerInput(points) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        scale = (scale * zoomChange).coerceIn(0.5f, 4f)
                        pan += panChange
                    }
                }
                .pointerInput(points, viewSize) {
                    detectTapGestures { tap ->
                        selected = nearestNode(tap, points, viewSize, scale, pan)
                    }
                },
        ) {
            val textMeasurer = rememberTextMeasurer()
            val labelStyle = TextStyle(color = colors.onSurface, fontSize = 11.sp)
            Canvas(Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                fun point(i: Int): Offset {
                    val (x, y) = points[i]
                    val base = Offset(32f + x * (w - 64f), 32f + y * (h - 64f))
                    return center + (base - center) * scale + pan
                }
                val pts = List(graph.nodes.size, ::point)
                // Edges under nodes.
                graph.edges.forEach { e ->
                    val a = indexById[e.fromId] ?: return@forEach
                    val b = indexById[e.toId] ?: return@forEach
                    val p0 = pts[a]
                    val p1 = pts[b]
                    val mid = (p0 + p1) / 2f
                    val dir = p1 - p0
                    val normal = Offset(-dir.y, dir.x)
                    val len = normal.getDistance()
                    val ctrl = if (len > 0f) mid + normal / len * (dir.getDistance() * 0.15f) else mid
                    edgePath.rewind()
                    edgePath.moveTo(p0.x, p0.y)
                    edgePath.quadraticTo(ctrl.x, ctrl.y, p1.x, p1.y)
                    drawPath(
                        edgePath,
                        colors.onSurfaceVariant,
                        style = Stroke(width = 2f),
                        alpha = 0.5f,
                    )
                }
                // Nodes on top, selected ringed and labelled.
                graph.nodes.forEachIndexed { i, node ->
                    val p = pts[i]
                    val r = if (i == selected) 26f else 18f
                    if (i == selected) {
                        drawCircle(colors.onSurface, radius = r + 6f, center = p, alpha = 0.25f)
                    }
                    drawCircle(palette[node.kind] ?: colors.outline, radius = r, center = p)
                    drawCircle(colors.surface, radius = r, center = p, style = Stroke(width = 3f))
                }
                selected?.let { s ->
                    if (s in graph.nodes.indices) {
                        val label = graph.nodes[s].label.ifBlank { graph.nodes[s].id }
                        drawText(textMeasurer, label, topLeft = pts[s] + Offset(30f, -40f), style = labelStyle)
                    }
                }
            }
        }
        val sel = selected?.takeIf { it in graph.nodes.indices }?.let { graph.nodes[it] }
        if (sel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sel.label.ifBlank { sel.id },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_close)) }
                Button(onClick = { onOpenFile(sel.id) }) { Text(stringResource(R.string.secondbrain_open)) }
            }
        }
    }
}

/** Nearest node to a tap in screen pixels, or null beyond the touch slop. */
private fun nearestNode(
    tap: Offset,
    points: List<Pair<Float, Float>>,
    size: IntSize,
    scale: Float,
    pan: Offset,
): Int? {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    if (w <= 0f || h <= 0f) return null
    val center = Offset(w / 2f, h / 2f)
    var best: Int? = null
    var bestDist = 72f
    points.forEachIndexed { i, (x, y) ->
        val base = Offset(32f + x * (w - 64f), 32f + y * (h - 64f))
        val p = center + (base - center) * scale + pan
        val d = (tap - p).getDistance()
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}
