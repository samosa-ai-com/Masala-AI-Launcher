package com.samosaai.masala.ui.home

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samosaai.masala.R
import com.samosaai.masala.data.Character
import com.samosaai.masala.ui.character.CharacterState
import com.samosaai.masala.ui.character.MasalaCharacter
import com.samosaai.masala.ui.theme.LocalMotion
import com.samosaai.masala.ui.theme.MasalaColors

/**
 * Listening (plan UI refresh U4, listening board): a glow around the screen edge that follows the
 * display's own rounded corners, Sam listening with rings that follow the mic, the words heard so far,
 * level bars, and Cancel / Stop and send / type instead. Reduced motion keeps the glow still.
 */
@Composable
fun ListeningOverlay(
    character: Character,
    level: () -> Float,
    words: String,
    source: String,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onType: () -> Unit,
) {
    val view = LocalView.current
    // The display's corner radius (Android 12+), so the glow hugs the glass; a phone-ish default otherwise.
    val cornerPx = remember(view) {
        if (Build.VERSION.SDK_INT >= 31) view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() else null
    } ?: (36f * view.resources.displayMetrics.density)
    val still = LocalMotion.current.reduced
    val spin by rememberInfiniteTransition(label = "glow").animateFloat(0f, if (still) 0f else 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "spin")
    val bars by rememberInfiniteTransition(label = "bars").animateFloat(0f, if (still) 0f else 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "bars")

    Box(Modifier.fillMaxSize().background(Color(0x990A0912))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 4.dp.toPx() + 6.dp.toPx() * level().coerceIn(0f, 1f)
            drawRoundRect(
                brush = Brush.sweepGradient(
                    listOf(Color(0xFF5B7CFF), MasalaColors.Indigo, MasalaColors.Gold, Color(0xFF5B7CFF)).let { c ->
                        // Turning the colour stops gives the same spin without redrawing a rotated layer.
                        val k = (spin / 360f * c.size).toInt() % c.size
                        c.drop(k) + c.take(k)
                    },
                    center,
                ),
                topLeft = Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Stroke(w),
            )
        }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(MasalaColors.Indigo.copy(alpha = 0.12f)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(MasalaColors.Indigo))
                Text(stringResource(R.string.listening_chip, source), fontSize = 12.5.sp, color = Color(0xFFC3D1FF), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            MasalaCharacter(character, CharacterState.LISTENING, Modifier.size(150.dp), level, description = stringResource(R.string.listening_sam))
            Text(
                words.ifBlank { stringResource(R.string.command_listening) },
                fontSize = 27.sp, lineHeight = 34.sp, textAlign = TextAlign.Center,
                color = if (words.isBlank()) MasalaColors.Text.copy(alpha = 0.6f) else MasalaColors.Text,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 26.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            // Level bars: heights follow the mic, each bar a little out of step with its neighbours.
            Row(Modifier.padding(top = 26.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val l = level().coerceIn(0f, 1f)
                repeat(14) { i ->
                    val wave = 0.5f + 0.5f * kotlin.math.sin((bars + i * 0.13f) * 2 * Math.PI.toFloat())
                    val h = 8f + 36f * (0.25f + 0.75f * l) * (if (still) 0.6f else wave)
                    Box(Modifier.width(5.dp).height(h.dp).clip(RoundedCornerShape(3.dp)).background(MasalaColors.Indigo))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.listening_hint), fontSize = 13.sp, color = MasalaColors.Text.copy(alpha = 0.72f), textAlign = TextAlign.Center)
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                RoundButton(Icons.Rounded.Close, stringResource(R.string.listening_cancel), onCancel)
                IconButton(onClick = onStop, modifier = Modifier.size(78.dp).clip(CircleShape).background(MasalaColors.Indigo)) {
                    Icon(Icons.Rounded.Mic, stringResource(R.string.listening_stop), tint = Color(0xFF0D1430), modifier = Modifier.size(30.dp))
                }
                RoundButton(Icons.Rounded.Keyboard, stringResource(R.string.listening_type), onType)
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
    ) { Icon(icon, label, tint = MasalaColors.Text) }
}
