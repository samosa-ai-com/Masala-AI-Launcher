package com.samosaai.masala.ui.home

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.samosaai.masala.agent.CommandState
import com.samosaai.masala.data.Character
import com.samosaai.masala.ui.character.MasalaCharacter
import com.samosaai.masala.ui.character.characterStateFor
import com.samosaai.masala.ui.theme.LocalMotion
import com.samosaai.masala.ui.theme.MasalaColors
import com.samosaai.masala.ui.theme.MasalaMono
import kotlinx.coroutines.delay

/** Words that make a run a layout run, which gets the skeleton preview. */
private val LAYOUT_RUN = Regex("""layout|home ?screen|my screen|widget""", RegexOption.IGNORE_CASE)

/**
 * The working sheet (plan UI refresh U4, working board): Sam thinking, how long it has taken, Stop, the
 * request, and the steps as they happen (`CommandState.Thinking.steps`), the current one pulsing. Layout
 * runs show a skeleton preview that fills in as sections arrive.
 */
@Composable
fun WorkingSheet(state: CommandState.Thinking, character: Character, onStop: () -> Unit, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            elapsed = (System.currentTimeMillis() - start) / 1000
            delay(1000)
        }
    }
    val layoutRun = LAYOUT_RUN.containsMatchIn(state.prompt) || state.steps.any { LAYOUT_RUN.containsMatchIn(it) }
    val still = LocalMotion.current.reduced
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(1f, if (still) 1f else 0.35f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    val shape = RoundedCornerShape(32.dp)
    Column(
        modifier.fillMaxWidth().padding(10.dp).clip(shape).background(Color(0xF0141018)).border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MasalaCharacter(character, characterStateFor(state, speaking = false, night = false), Modifier.size(54.dp), description = stringResource(R.string.working_sam))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (layoutRun) R.string.working_layout else R.string.working_title), style = MaterialTheme.typography.titleMedium, color = MasalaColors.Text)
                Text(
                    "%d:%02d".format(elapsed / 60, elapsed % 60) + if (layoutRun) " · " + stringResource(R.string.working_layout_usual) else "",
                    fontFamily = MasalaMono, fontSize = 12.5.sp, color = MasalaColors.Text.copy(alpha = 0.62f), maxLines = 1,
                )
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.height(36.dp)) { Text(stringResource(R.string.working_stop), color = MasalaColors.Text) }
        }
        if (state.prompt.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    state.prompt, fontSize = 14.sp, color = Color(0xFFF6E7C4),
                    modifier = Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)).background(MasalaColors.Gold.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Column(Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            state.steps.forEach { step -> StepRow(step, done = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(22.dp).alpha(pulse).clip(CircleShape).border(2.dp, MasalaColors.Gold, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MasalaColors.Gold))
                }
                Column(Modifier.weight(1f).padding(bottom = 12.dp)) {
                    Text(stringResource(if (state.steps.isEmpty()) R.string.working_starting else R.string.working_next), fontSize = 14.5.sp, color = Color(0xFFF2D9A0), style = MaterialTheme.typography.titleSmall)
                    if (layoutRun) Skeleton(still)
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: String, done: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(if (done) MasalaColors.Cardamom else Color.Transparent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Color(0xFF06200F), modifier = Modifier.size(13.dp))
            }
            Box(Modifier.width(2.dp).height(16.dp).background(MasalaColors.Cardamom.copy(alpha = 0.4f)))
        }
        Text(stringResource(R.string.skill_step_done, step), fontSize = 14.5.sp, color = MasalaColors.Text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 12.dp))
    }
}

/** A phone-shaped placeholder that shimmers while the layout is being made. */
@Composable
private fun Skeleton(still: Boolean) {
    val shimmer by rememberInfiniteTransition(label = "sk").animateFloat(0.06f, if (still) 0.06f else 0.16f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "shimmer")
    val bar = Color.White.copy(alpha = shimmer)
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.04f)).border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(70.dp).height(128.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2A1A33)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(10.dp)).background(bar))
            Box(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(10.dp)).background(bar))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(3) { Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(10.dp)).background(bar)) } }
            Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(10.dp)).background(bar))
        }
        Column(Modifier.weight(1f).height(128.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            listOf(0.9f, 0.7f, 0.8f).forEach { Box(Modifier.fillMaxWidth(it).height(10.dp).clip(RoundedCornerShape(10.dp)).background(bar)) }
            Text(stringResource(R.string.working_preview_note), fontSize = 11.5.sp, color = MasalaColors.Text.copy(alpha = 0.5f))
        }
    }
}
