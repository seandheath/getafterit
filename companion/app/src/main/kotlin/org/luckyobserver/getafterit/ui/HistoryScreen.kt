package org.luckyobserver.getafterit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.luckyobserver.getafterit.metrics.RunSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/** Every recorded and imported activity, newest first, and importing more. */
@Composable
fun HistoryScreen(
    runs: List<RunSummary>,
    miles: Boolean,
    importStatus: String?,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onOpenRun: (RunSummary) -> Unit,
    onBack: () -> Unit,
) {
    Page {
        Header("History", onBack = onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportFolder) { Text("Import folder") }
            OutlinedButton(onClick = onImportFiles) { Text("Import files") }
        }
        Text(
            "GPX, KML or KMZ files. From OpenTracks: Settings, Import/Export, export each track as a file into " +
                "a folder, then import that folder here. Runs already here are skipped.",
            style = MaterialTheme.typography.bodySmall,
        )
        importStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (runs.isEmpty()) Text("No runs yet. Each run is saved here when it stops.", style = MaterialTheme.typography.bodyLarge)
        for (run in runs) {
            Card(Modifier.fillMaxWidth().clickable { onOpenRun(run) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(run.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOf(
                            DATE.format(Instant.ofEpochMilli(run.startMs)),
                            activityName(run.activity),
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${formatDistance(run.distM, miles)} · ${formatDuration(run.movingMs / 1000)} · " +
                            "+${formatElevation(run.gainM.toLong(), miles)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/** "trail_running" → "Trail running"; OpenTracks' "unknown" (no type chosen) shows nothing. */
fun activityName(id: String): String =
    if (id == "unknown") "" else id.replace('_', ' ').replaceFirstChar { it.uppercase() }
