package org.luckyobserver.getafterit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.luckyobserver.getafterit.course.CourseStore
import org.luckyobserver.getafterit.settings.followsCourse
import org.luckyobserver.getafterit.settings.MODE_NAMES
import org.luckyobserver.getafterit.settings.MODE_WORKOUT

/** Before a run: is the watch there, which course, and a big Start. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadyScreen(
    mode: Int,
    enabledModes: List<Int>,
    onModePicked: (Int) -> Unit,
    watchConnected: Boolean,
    course: CourseStore.Saved?,
    courseError: String?,
    miles: Boolean,
    onLoadCourse: () -> Unit,
    onClearCourse: () -> Unit,
    savedCourses: () -> List<String>,
    onPickCourse: (String) -> Unit,
    onHistory: () -> Unit,
    onStart: () -> Unit,
    onSettings: () -> Unit,
) {
    Page {
        Header("Get After It", logo = true, onSettings = onSettings)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in enabledModes) {
                val name = MODE_NAMES[i]
                if (i == mode) Button(onClick = {}) { Text(name) } else OutlinedButton(onClick = { onModePicked(i) }) { Text(name) }
            }
        }
        val workout = mode == MODE_WORKOUT
        val courseMode = followsCourse(mode)
        if (!workout) PhoneMap(if (courseMode) course else null, trail = emptyList())

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Watch", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (watchConnected) "Connected" else "Not connected. Open the Pebble app and check the watch is paired.",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (courseMode) Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Course", style = MaterialTheme.typography.labelLarge)
                if (course == null) {
                    Text("None loaded", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(course.name, style = MaterialTheme.typography.titleMedium)
                    Text(formatDistance(course.course.totalM, miles), style = MaterialTheme.typography.bodyLarge)
                }
                courseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                var picking by remember { mutableStateOf<List<String>?>(null) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onLoadCourse) { Text(if (course == null) "Load GPX" else "Change") }
                    OutlinedButton(onClick = { picking = savedCourses() }) { Text("Saved courses") }
                    if (course != null) OutlinedButton(onClick = onClearCourse) { Text("Clear") }
                }
                picking?.let { names ->
                    AlertDialog(
                        onDismissRequest = { picking = null },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
                        title = { Text("Saved courses") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                if (names.isEmpty()) Text("None yet. Every GPX you load is kept here.")
                                for (name in names) {
                                    TextButton(onClick = {
                                        onPickCourse(name)
                                        picking = null
                                    }) { Text(name) }
                                }
                            }
                        },
                    )
                }
            }
        }

        OutlinedButton(onClick = onHistory) { Text("History") }

        if (workout) {
            Text(
                "Hold Select on the watch to start. A workout tracks time and heart rate on the watch only; " +
                    "nothing is recorded on the phone.",
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            BigButton("Start ${MODE_NAMES[mode].lowercase()}", onClick = onStart)
            Text("Or pick the activity on the watch when its app opens, then hold Select.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
