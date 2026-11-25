package org.luckyobserver.getafterit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.luckyobserver.getafterit.settings.MAP_SCREENS
import org.luckyobserver.getafterit.settings.MAP_SLOTS
import org.luckyobserver.getafterit.settings.OVERLAY_NAMES
import org.luckyobserver.getafterit.settings.MAP_MODES
import org.luckyobserver.getafterit.settings.MODE_NAMES
import org.luckyobserver.getafterit.settings.MODE_RUN
import org.luckyobserver.getafterit.settings.MapScreens
import org.luckyobserver.getafterit.settings.followsCourse
import org.luckyobserver.getafterit.settings.STATS_SCREENS
import org.luckyobserver.getafterit.settings.STAT_NAMES
import org.luckyobserver.getafterit.settings.STAT_SLOTS
import org.luckyobserver.getafterit.settings.Settings
import org.luckyobserver.getafterit.settings.TILES_CUSTOM
import org.luckyobserver.getafterit.settings.TILES_OFFLINE
import org.luckyobserver.getafterit.settings.TILE_SOURCE_NAMES

// General, then one tab per recorded activity: the map ones, then Run.
private val SETTINGS_TABS = listOf("General") + MAP_MODES.map { MODE_NAMES[it] } + MODE_NAMES[MODE_RUN]
private val MAP_POSITIONS = listOf("Top left / bar left", "Top right / bar right", "Bottom left", "Bottom right", "Top centre (value only)")
private val STAT_POSITIONS = listOf("Row 1 left", "Row 1 right", "Row 2 left", "Row 2 right", "Row 3 left", "Row 3 right")

/** Edits a copy of [initial]; [onSave] gets the result once it passes [Settings.problem]. */
@Composable
fun SettingsScreen(
    initial: Settings,
    offline: OfflineMapState,
    strap: StrapState,
    onSave: (Settings) -> Unit,
    onCancel: () -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    var maxHrText by remember { mutableStateOf(initial.maxHr.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    var tab by remember { mutableIntStateOf(0) }

    Page {
        Header("Settings", onBack = onCancel)
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            SETTINGS_TABS.forEachIndexed { i, name -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) }) }
        }

        when (tab) {
            0 -> {
                Section("General")
                Choice("Units", listOf("Kilometres", "Miles"), if (s.miles) 1 else 0) { s = s.copy(miles = it == 1) }
                OutlinedTextField(
                    value = maxHrText,
                    onValueChange = { maxHrText = it },
                    label = { Text("Max heart rate (for zones)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Toggle("Keep backlight on", s.backlight) { s = s.copy(backlight = it) }
                Toggle("Heart rate from chest strap", s.strapHr) { s = s.copy(strapHr = it) }
                if (s.strapHr) {
                    Text("Strap: ${strap.name ?: "none paired"}", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = strap.onPair) { Text(if (strap.name == null) "Pair strap" else "Pair a different strap") }
                        if (strap.name != null) OutlinedButton(onClick = strap.onForget) { Text("Forget") }
                    }
                    Text(
                        "Wear the strap so it's awake, then pick it. Get After It reads it while the watch app is open or a run " +
                            "is recording, and saves it in the run's GPX; the watch uses its own sensor when there's no recent " +
                            "strap reading.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Section("Activities on the watch")
                Text(
                    "The watch app opens on a list of these. Track activities follow the course " +
                        "loaded on the Ready screen; Record ones show the map without one; Run shows only stats; Workout " +
                        "times heart rate on the watch and records nothing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                MODE_NAMES.forEachIndexed { m, name ->
                    Toggle(name, s.modeEnabled(m)) { on -> s = s.copy(modes = if (on) s.modes or (1 shl m) else s.modes and (1 shl m).inv()) }
                }

                Section("Map")
                Choice("Orientation", listOf("North up", "Forwards up"), if (s.forwardsUp) 1 else 0) {
                    s = s.copy(forwardsUp = it == 1)
                }
                Choice("Map tiles", TILE_SOURCE_NAMES, s.tileSource) { s = s.copy(tileSource = it) }
                when (s.tileSource) {
                    TILES_OFFLINE -> OfflineMapSection(offline)
                    TILES_CUSTOM -> OutlinedTextField(
                        value = s.customTileUrl,
                        onValueChange = { s = s.copy(customTileUrl = it.trim()) },
                        label = { Text("Tile URL") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> Text(
                        "Tiles download as you go, using mobile data, and stay cached for areas you've seen. " +
                            "OpenStreetMap doesn't allow downloading maps ahead of time; for no-signal areas use an offline map file.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Choice("Overlay", OVERLAY_NAMES, s.overlay) { s = s.copy(overlay = it) }
                Toggle("Off-course warning (Track activities)", s.offCourseAlert) { s = s.copy(offCourseAlert = it) }
            }
            in 1..MAP_MODES.size -> {
                val n = tab - 1
                Section(MODE_NAMES[MAP_MODES[n]])
                if (followsCourse(MAP_MODES[n])) {
                    Text("Follows the course loaded on the Ready screen.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Shows the map and records your track, without a course.", style = MaterialTheme.typography.bodySmall)
                }
                MapModeScreens(s.mapScreens[n]) { screens ->
                    s = s.copy(mapScreens = s.mapScreens.toMutableList().also { it[n] = screens })
                }
            }
            else -> {
                Section("Run")
                Text(
                    "Up/Down switch between these screens on the watch; screens with every field set to None are skipped.",
                    style = MaterialTheme.typography.bodySmall,
                )
                for (screen in 0 until STATS_SCREENS) {
                    for (pos in 0 until STAT_SLOTS) {
                        val i = screen * STAT_SLOTS + pos
                        Choice("Screen ${screen + 1}: ${STAT_POSITIONS[pos]}", STAT_NAMES, s.statsSlots[i]) { v ->
                            s = s.copy(statsSlots = s.statsSlots.toMutableList().also { it[i] = v })
                        }
                    }
                }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = {
                // A mode just turned off can't stay selected; fall back to the first one still on.
                val mode = if (s.modeEnabled(s.mode)) s.mode else MODE_NAMES.indices.firstOrNull { s.modeEnabled(it) } ?: s.mode
                val result = s.copy(maxHr = maxHrText.toIntOrNull() ?: -1, mode = mode)
                error = result.problem()
                    ?: "Pick an offline map file first.".takeIf { result.tileSource == TILES_OFFLINE && offline.mapName == null }
                if (error == null) onSave(result)
            }) { Text("Save") }
        }
    }
}

/** The paired chest strap and the actions that change it. */
class StrapState(val name: String?, val onPair: () -> Unit, val onForget: () -> Unit)

/** The imported offline map and style, and the actions that change them. */
class OfflineMapState(
    val mapName: String?,
    val themeName: String?,
    val status: String?,
    val onPickMap: () -> Unit,
    val onPickTheme: () -> Unit,
    val onClearTheme: () -> Unit,
    val onOpenPage: (String) -> Unit,
)

@Composable
private fun OfflineMapSection(o: OfflineMapState) {
    Text(
        "Offline maps work with no signal and never expire. OpenAndroMaps only allows downloading them yourself, " +
            "so: 1. Open their downloads page and download your region (e.g. Maine). " +
            "2. Optionally download the Elevate style for trails and contour lines. " +
            "3. Pick the downloaded files here. They're copied into Get After It.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { o.onOpenPage("https://www.openandromaps.org/en/downloads") }) { Text("Map downloads") }
        OutlinedButton(onClick = { o.onOpenPage("https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme") }) {
            Text("Elevate style")
        }
    }
    Text("Map: ${o.mapName ?: "none"}", style = MaterialTheme.typography.bodyLarge)
    Button(onClick = o.onPickMap) { Text(if (o.mapName == null) "Pick map file (.zip or .map)" else "Replace map file") }
    Text("Style: ${o.themeName ?: "built-in (basic, no contours)"}", style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = o.onPickTheme) { Text("Pick style (.zip)") }
        if (o.themeName != null) OutlinedButton(onClick = o.onClearTheme) { Text("Use built-in") }
    }
    o.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

/** A map activity's map screens and its stats page (Select switches between them on the watch). */
@Composable
private fun MapModeScreens(screens: MapScreens, onChange: (MapScreens) -> Unit) {
    val map = screens.map
    val stats = screens.stats
    Text(
        "Up/Down switch between the map screens on the watch; screens with every field set to None are skipped. " +
            "Hold Up/Down to zoom. Select switches to the stats page.",
        style = MaterialTheme.typography.bodySmall,
    )
    for (screen in 0 until MAP_SCREENS) {
        for (pos in 0 until MAP_SLOTS) {
            val i = screen * MAP_SLOTS + pos
            Choice("Screen ${screen + 1}: ${MAP_POSITIONS[pos]}", STAT_NAMES, map[i]) { v ->
                onChange(screens.copy(map = map.toMutableList().also { it[i] = v }))
            }
        }
    }
    Section("Stats page")
    for (i in 0 until STAT_SLOTS) {
        Choice(STAT_POSITIONS[i], STAT_NAMES, stats[i]) { v -> onChange(screens.copy(stats = stats.toMutableList().also { it[i] = v })) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A labelled dropdown choosing one of [options] by index. */
@Composable
private fun Choice(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(options[selected]) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(i); open = false })
                }
            }
        }
    }
}
