package org.luckyobserver.getafterit.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.luckyobserver.getafterit.course.CourseStore
import org.luckyobserver.getafterit.course.LatLon
import org.luckyobserver.getafterit.map.Line
import org.luckyobserver.getafterit.map.TileStore
import org.luckyobserver.getafterit.map.TileXY
import org.luckyobserver.getafterit.map.composeView
import org.luckyobserver.getafterit.map.tileKey
import org.luckyobserver.getafterit.map.viewTiles
import org.luckyobserver.getafterit.map.worldPixel
import org.luckyobserver.getafterit.settings.Settings
import org.luckyobserver.getafterit.settings.SettingsStore

private const val MAP_W = 720
private const val MAP_H = 440
private const val ZOOM = 16
private const val COURSE_RGB = 0xFF00FF
private const val TRAIL_RGB = 0x0000FF
private const val MARKER_RGB = 0xE00000
private const val MARKER_R = 9

/**
 * North-up map of where the phone is, with the course and [trail], drawn from
 * the same tile cache as the watch map. Uses the phone's GPS while it's shown;
 * the setup screen makes sure location is granted before this appears.
 */
@SuppressLint("MissingPermission")
@Composable
fun PhoneMap(course: CourseStore.Saved?, trail: List<LatLon>) {
    val context = LocalContext.current
    var fix by remember { mutableStateOf<LatLon?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val store = remember { TileStore(context) }
    val decoded = remember { HashMap<TileXY, IntArray>() }
    var decodedKey by remember { mutableStateOf("") }
    // One render at a time: a superseded render keeps running and shares [decoded].
    @OptIn(ExperimentalCoroutinesApi::class)
    val renderer = remember { Dispatchers.IO.limitedParallelism(1) }

    DisposableEffect(Unit) {
        val lm = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { fix = LatLon(it.latitude, it.longitude) }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        onDispose { lm.removeUpdates(listener) }
    }

    // Before the first fix, show the start of the course if there is one.
    val center = fix ?: course?.course?.points?.first()
    LaunchedEffect(center, course, trail) {
        val c = center ?: return@LaunchedEffect
        val settings = SettingsStore.get(context)
        image = withContext(renderer) {
            if (tileKey(settings) != decodedKey) {
                decoded.clear()
                decodedKey = tileKey(settings)
            }
            render(store, settings, decoded, c, fix != null, course, trail)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(240.dp)) {
            val img = image
            if (img == null) {
                Text("Waiting for GPS…", style = MaterialTheme.typography.bodyLarge)
            } else {
                Image(img, contentDescription = "Map", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth())
            }
        }
        Text(
            SettingsStore.get(context).attribution,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun render(
    store: TileStore, settings: Settings, decoded: HashMap<TileXY, IntArray>, centerLatLon: LatLon, showMarker: Boolean,
    course: CourseStore.Saved?, trail: List<LatLon>,
): ImageBitmap? {
    val center = worldPixel(centerLatLon.lat, centerLatLon.lon, ZOOM)
    val needed = viewTiles(center, 0.0, MAP_W, MAP_H)
    decoded.keys.retainAll(needed.toSet())
    for (t in needed) if (t !in decoded) store.tile(settings, ZOOM, t.x, t.y)?.let { decoded[t] = it }
    if (decoded.isEmpty()) return null
    val lines = buildList {
        course?.let { add(Line(it.course.points.map { p -> worldPixel(p.lat, p.lon, ZOOM) }, COURSE_RGB, 5)) }
        add(Line(trail.map { p -> worldPixel(p.lat, p.lon, ZOOM) }, TRAIL_RGB, 5))
    }
    val rgb = composeView(center, 0.0, MAP_W, MAP_H, decoded, lines)
    if (showMarker) {
        for (dy in -MARKER_R..MARKER_R) for (dx in -MARKER_R..MARKER_R) {
            if (dx * dx + dy * dy <= MARKER_R * MARKER_R) rgb[(MAP_H / 2 + dy) * MAP_W + MAP_W / 2 + dx] = MARKER_RGB
        }
    }
    for (i in rgb.indices) rgb[i] = rgb[i] or (0xFF shl 24)
    return Bitmap.createBitmap(rgb, MAP_W, MAP_H, Bitmap.Config.ARGB_8888).asImageBitmap()
}
