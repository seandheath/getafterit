package org.luckyobserver.getafterit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Something Get After It needs that isn't set up yet, with the step that fixes it. */
class SetupItem(
    val title: String,
    val why: String,
    val action: String,
    val enabled: Boolean = true,
    val onAction: () -> Unit,
)

/** Shown only while something in [missing] is outstanding. */
@Composable
fun SetupScreen(missing: List<SetupItem>) {
    Page {
        Header("Set up Get After It", logo = true)
        Text(
            "A few things are needed before your first run. This screen goes away once they're done.",
            style = MaterialTheme.typography.bodyLarge,
        )
        for (item in missing) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(item.why, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                    Button(onClick = item.onAction, enabled = item.enabled) { Text(item.action) }
                }
            }
        }
    }
}
