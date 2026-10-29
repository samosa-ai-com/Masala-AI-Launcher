package com.samosaai.masala.ui.wiki

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.R
import kotlinx.coroutines.launch

/**
 * What the model wants to remember, with Approve and Dismiss (arch §3.5: writes go through an approval
 * chip). Shown on the reply card, in the skill sheet and in the wiki screen.
 */
@Composable
fun ProposalCards(app: MasalaApp, modifier: Modifier = Modifier) {
    val proposals by app.wiki.proposals.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        proposals.forEach { p ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.wiki_remember, p.title), style = MaterialTheme.typography.titleSmall)
                    Text(p.markdown, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                    if (p.reason.isNotBlank()) Text(p.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Row {
                        TextButton(onClick = { scope.launch { app.wiki.approve(p.id) } }) { Text(stringResource(R.string.wiki_approve)) }
                        TextButton(onClick = { app.wiki.dismiss(p.id) }) { Text(stringResource(R.string.wiki_dismiss)) }
                    }
                }
            }
        }
    }
}
