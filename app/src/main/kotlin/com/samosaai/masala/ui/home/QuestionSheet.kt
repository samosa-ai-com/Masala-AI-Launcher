package com.samosaai.masala.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.R
import com.samosaai.masala.actions.QuestionGate

/**
 * The `question` tool's turn: what Masala asks, the options as buttons, and a field for an
 * answer in the user's own words. Swiping away or dismissing answers nothing, and the run
 * carries on without it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionSheet(gate: QuestionGate) {
    val request by gate.pending.collectAsStateWithLifecycle()
    val r = request ?: return
    var custom by rememberSaveable(r) { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { gate.dismiss(r) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.question_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(r.question, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
            r.options.forEachIndexed { i, option ->
                OutlinedButton(onClick = { gate.answer(r, i) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(option, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.question_custom_hint)) },
                    singleLine = false,
                    maxLines = 3,
                )
                Button(onClick = { gate.answer(r, custom) }, enabled = custom.isNotBlank()) {
                    Text(stringResource(R.string.question_send))
                }
            }
        }
    }
}
