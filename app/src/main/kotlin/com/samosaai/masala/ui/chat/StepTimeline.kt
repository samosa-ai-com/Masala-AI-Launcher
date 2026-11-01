package com.samosaai.masala.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samosaai.masala.R
import com.samosaai.masala.ui.theme.LocalMotion
import com.samosaai.masala.ui.theme.MasalaColors
import com.samosaai.masala.ui.theme.MasalaMono
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.delay

/**
 * What the agent is doing, as it does it: one row per tool it has called, joined by a
 * vertical line of dots, with the step it is on pulsing below and a Stop beside the clock.
 * This is the timeline the working sheet showed; it lives inside the thread now, so the
 * conversation stays readable while it runs.
 */
@Composable
fun StepTimeline(
    steps: List<String>,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        // Monotonic: a network time update must not jump the stopwatch.
        val start = android.os.SystemClock.elapsedRealtime()
        while (true) {
            elapsed = (android.os.SystemClock.elapsedRealtime() - start) / 1000
            delay(1_000)
        }
    }
    val still = LocalMotion.current.reduced
    val pulse by rememberInfiniteTransition(label = "step").animateFloat(
        1f,
        if (still) 1f else 0.35f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // Steps already taken: a tick, the tool's name, and the line down to the next.
        steps.forEach { step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).background(MasalaColors.Cardamom),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Check, null, tint = Color(0xFF06200F), modifier = Modifier.size(12.dp)) }
                    Box(Modifier.width(2.dp).height(14.dp).background(MasalaColors.Cardamom.copy(alpha = 0.4f)))
                }
                Text(
                    // A consult reads as itself ("Consulting Doctor"); other steps say "Ran …".
                    if (step.startsWith("Consulting ")) step else stringResource(R.string.skill_step_done, step),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MasalaTheme.tokens.textHigh,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
        // The step in flight: a pulsing ring, then the clock and Stop.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(20.dp).alpha(pulse).clip(CircleShape).border(2.dp, MasalaColors.Gold, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(7.dp).clip(CircleShape).background(MasalaColors.Gold)) }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (steps.isEmpty()) R.string.working_starting else R.string.working_next),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFF2D9A0),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "%d:%02d".format(elapsed / 60, elapsed % 60),
                        fontFamily = MasalaMono,
                        fontSize = 12.sp,
                        color = MasalaTheme.tokens.textLow,
                    )
                    androidx.compose.material3.TextButton(onClick = onStop) {
                        Icon(Icons.Rounded.Stop, null, tint = MasalaTheme.tokens.textMedium, modifier = Modifier.size(14.dp))
                        Text(
                            stringResource(R.string.working_stop),
                            color = MasalaTheme.tokens.textMedium,
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
