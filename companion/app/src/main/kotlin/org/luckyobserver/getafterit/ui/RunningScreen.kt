package org.luckyobserver.getafterit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.luckyobserver.getafterit.course.CourseStore
import org.luckyobserver.getafterit.course.LatLon
import org.luckyobserver.getafterit.pebble.LiveRun

/** During a run: a few large numbers, how fresh they are, and Stop. */
@Composable
fun RunningScreen(
    stats: LiveRun.Stats?,
    remainingM: Double?,
    course: CourseStore.Saved?,
    trail: List<LatLon>,
    nowMs: Long,
    miles: Boolean,
    paused: Boolean,
    hiking: Boolean,
    onStop: () -> Unit,
    onSettings: () -> Unit,
) {
    Page {
        Header(if (paused) "Paused" else if (hiking) "Hiking" else "Running", onSettings = onSettings)

        if (stats == null) {
            Text("Starting…", style = MaterialTheme.typography.titleMedium)
            PhoneMap(course, trail)
        } else {
            BigStat("Moving time", formatDuration(stats.movingS))
            BigStat("Distance", formatDistance(stats.distM.toDouble(), miles))
            BigStat("Pace", formatPace(stats.paceSKm, miles))
            PhoneMap(course, trail)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SmallStat("Gain", formatElevation(stats.gainM, miles), Modifier.weight(1f))
                SmallStat("Loss", formatElevation(stats.lossM, miles), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SmallStat("Strap HR", stats.strapHr?.toString() ?: "--", Modifier.weight(1f))
                SmallStat("To go", remainingM?.let { formatDistance(it, miles) } ?: "--", Modifier.weight(1f))
            }
            Text(
                "Updated ${(nowMs - stats.atMs).coerceAtLeast(0) / 1000} s ago",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        StopControl(onStop)
    }
}

@Composable
private fun BigStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, fontSize = 56.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmallStat(label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}
