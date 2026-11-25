package org.luckyobserver.getafterit.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.luckyobserver.getafterit.R

/** Scrollable page that keeps clear of the status and gesture bars (edge-to-edge on Android 15). */
@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Title row; [logo] shows the app logo, [onSettings] adds a gear button, [onBack] a back arrow. */
@Composable
fun Header(title: String, logo: Boolean = false, onSettings: (() -> Unit)? = null, onBack: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (logo) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
        }
        onBack?.let {
            TextButton(onClick = it, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Text("←", fontSize = 24.sp)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        onSettings?.let {
            TextButton(onClick = it, modifier = Modifier.semantics { contentDescription = "Settings" }) {
                Text("⚙", fontSize = 26.sp)
            }
        }
    }
}

@Composable
fun BigButton(label: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                 else ButtonDefaults.buttonColors(),
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Text(label, fontSize = 20.sp)
    }
}

/**
 * Stop asks first: "Keep running" takes Stop's place and the real stop sits
 * lower down, so a double tap can't end the run.
 */
@Composable
fun StopControl(onStop: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (!confirming) {
        BigButton("Stop run", onClick = { confirming = true })
        return
    }
    BigButton("Keep running", onClick = { confirming = false })
    Spacer(Modifier.height(80.dp))
    Text("Stop the run? It's saved as a GPX file.", style = MaterialTheme.typography.bodyLarge)
    BigButton("Yes, stop run", onClick = onStop, danger = true)
}
