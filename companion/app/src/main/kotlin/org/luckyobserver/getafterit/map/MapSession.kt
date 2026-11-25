package org.luckyobserver.getafterit.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.luckyobserver.getafterit.course.CourseStore
import org.luckyobserver.getafterit.course.LatLon
import org.luckyobserver.getafterit.course.haversine
import org.luckyobserver.getafterit.metrics.PaceFilter
import org.luckyobserver.getafterit.pebble.Keys
import org.luckyobserver.getafterit.pebble.LiveRun
import org.luckyobserver.getafterit.pebble.PebbleMessenger
import org.luckyobserver.getafterit.pebble.RunSession
import org.luckyobserver.getafterit.settings.followsCourse
import org.luckyobserver.getafterit.settings.showsMap
import org.luckyobserver.getafterit.settings.SettingsStore
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The phone's GPS, while the watchapp is open or a run is recording, and the
 * watch's map: the course and the run's breadcrumb trail, rendered from cached
 * tiles and sent as GColor8 images in [CHUNK]-byte pieces. Between images only
 * the marker moves. Each fix also goes to [onRunFix] with the filtered speed.
 */
class MapSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onRunFix: (Location, Double?) -> Unit,
) {

    private class View(
        val center: World, val heading: Double, val zoom: Int,
        val course: CourseStore.Saved?, val tileKey: String, val scrollX: Int, val scrollY: Int,
    )

    private fun tileKey() = tileKey(SettingsStore.get(context))

    // Every field below is touched only on this single-threaded dispatcher.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.Default.limitedParallelism(1)

    private val tiles = TileStore(context)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var zoom = 16
    // How far the user dragged the watch map from their position, in screen
    // pixels; while scrolled the map holds still and keeps [scrollHeading].
    private var scrollX = 0
    private var scrollY = 0
    private var scrollHeading: Double? = null
    private var view: View? = null
    private var heading: Double? = null  // last GPS bearing, kept while stationary
    private var lastFix: LatLon? = null
    private var composing = false
    private var retryAt = 0L  // elapsedRealtime before which a failed image isn't retried
    private val trail = mutableListOf<LatLon>()
    private var trailRun: Long? = null
    private val paceFilter = PaceFilter()

    private val listener = LocationListener { loc -> scope.launch(serial) { onFix(loc) } }

    fun start() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "no location permission")
            scope.launch { PebbleMessenger.sendError(context, "Allow location for Get After It") }
            return
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        Log.d(TAG, "GPS started")
        // Draw the map from where the phone last was rather than waiting for a
        // fresh fix (seconds when the GPS is cold); the first fix recentres it.
        val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: return
        scope.launch(serial) { if (drawsMap() && view == null && !composing) compose(LatLon(last.latitude, last.longitude)) }
    }

    fun stop() {
        locationManager.removeUpdates(listener)
        scope.launch(serial) { view = null }
        Log.d(TAG, "GPS stopped")
    }

    /** Zoom and scroll from the watch; redraws only when they change (the watch repeats them). */
    fun setView(z: Int, x: Int, y: Int) {
        scope.launch(serial) {
            if (z == zoom && x == scrollX && y == scrollY) return@launch
            val scrolled = x != 0 || y != 0
            scrollHeading = if (!scrolled) null else scrollHeading ?: view?.heading ?: mapHeading()
            zoom = z
            scrollX = x
            scrollY = y
            lastFix?.let { if (drawsMap() && !composing) compose(it) }
        }
    }

    private suspend fun onFix(loc: Location) {
        if (!loc.hasAccuracy() || loc.accuracy > MAX_ACCURACY_M) return
        val fix = LatLon(loc.latitude, loc.longitude)
        lastFix = fix
        val atMs = loc.elapsedRealtimeNanos / 1_000_000
        val speed = paceFilter.onFix(atMs, if (loc.hasSpeed()) loc.speed else null, loc.accuracy)
        LiveRun.speed = LiveRun.Speed(speed, atMs)
        onRunFix(loc, speed)
        if (loc.hasBearing() && loc.speed > MOVING_SPEED_MPS) heading = loc.bearing.toDouble()
        updateTrail(fix)
        val msg = mutableMapOf<UInt, PebbleDictionaryItem>(Keys.FIX to PebbleDictionaryItem.UInt8(1))
        if (loc.hasAltitude()) msg[Keys.ELEV] = PebbleDictionaryItem.Int32(loc.altitude.roundToInt())
        val p = course()?.course?.progress(fix)
        LiveRun.remainingM.value = p?.remainingM
        if (!RunSession.watchAppOpen) {
            view = null  // so reopening the watchapp draws a fresh map
            return
        }
        msg[Keys.REMAIN] = PebbleDictionaryItem.Int32(p?.remainingM?.roundToInt() ?: -1)
        msg[Keys.OFF_COURSE] = PebbleDictionaryItem.UInt8(if (p?.offCourse == true && SettingsStore.get(context).offCourseAlert) 1 else 0)
        view?.let { msg.putAll(marker(it, fix)) }
        PebbleMessenger.send(context, msg)
        if (!drawsMap()) {
            view = null  // so switching back to Trail draws a fresh map
            return
        }
        if (!composing && SystemClock.elapsedRealtime() >= retryAt && needsNewImage(fix)) compose(fix)
    }

    /** Breadcrumb of the current run, restarted when a new run begins. */
    private fun updateTrail(fix: LatLon) {
        val run = RunSession.startedAt(context)
        if (run != trailRun) {
            trail.clear()
            LiveRun.trail.value = emptyList()
            trailRun = run
        }
        if (run == null) return
        if (trail.isEmpty() || haversine(trail.last(), fix) >= TRAIL_STEP_M) {
            trail += fix
            LiveRun.trail.value = trail.toList()
        }
    }

    private fun forwardsUp() = SettingsStore.get(context).forwardsUp

    // Run keeps the GPS for pace and distance but shows no map.
    private fun drawsMap() = showsMap(SettingsStore.get(context).mode)

    // Only the Track activities follow the loaded course; Record ones just record the breadcrumb.
    private fun course() = if (followsCourse(SettingsStore.get(context).mode)) CourseStore.get(context) else null

    private fun mapHeading(): Double = scrollHeading ?: if (forwardsUp()) heading ?: 0.0 else 0.0

    private fun needsNewImage(fix: LatLon): Boolean {
        val v = view ?: return true
        if (v.zoom != zoom || v.course !== course() || v.tileKey != tileKey()) return true
        if (v.scrollX != scrollX || v.scrollY != scrollY) return true
        // Scrolled away: the map stays put while the runner moves.
        if (scrollX != 0 || scrollY != 0) return false
        if (forwardsUp() && angleDiff(mapHeading(), v.heading) > HEADING_STEP) return true
        val c = worldPixel(fix.lat, fix.lon, v.zoom)
        val s = worldToScreen(c.x - v.center.x, c.y - v.center.y, v.heading)
        return abs(s.u) > RECENTER_PX || abs(s.v) > RECENTER_PX
    }

    private fun marker(v: View, fix: LatLon): Map<UInt, PebbleDictionaryItem> {
        val c = worldPixel(fix.lat, fix.lon, v.zoom)
        val s = worldToScreen(c.x - v.center.x, c.y - v.center.y, v.heading)
        val dir = heading?.let { ((it - v.heading + 360) % 360).roundToInt() } ?: -1
        return mapOf(
            Keys.MARKER_X to PebbleDictionaryItem.Int32((s.u + W / 2).roundToInt()),
            Keys.MARKER_Y to PebbleDictionaryItem.Int32((s.v + H / 2).roundToInt()),
            Keys.MARKER_DIR to PebbleDictionaryItem.Int32(dir),
        )
    }

    private suspend fun compose(fix: LatLon) {
        composing = true
        try {
            val z = zoom
            val h = mapHeading()
            val settings = SettingsStore.get(context)
            val key = tileKey()
            val offset = screenToWorld(scrollX.toDouble(), scrollY.toDouble(), h)
            val here = worldPixel(fix.lat, fix.lon, z)
            val center = World(here.x + offset.x, here.y + offset.y)
            val tileList = viewTiles(center, h, W, H)
            val loaded = withContext(Dispatchers.IO) {
                tileList.mapNotNull { t -> tiles.tile(settings, z, t.x, t.y)?.let { t to it } }.toMap()
            }
            if (loaded.isEmpty()) {
                // Usually no signal; don't hit the network again on every fix.
                retryAt = SystemClock.elapsedRealtime() + RETRY_MS
                PebbleMessenger.sendError(context, "No map tiles (offline?)")
                return
            }
            val lines = mutableListOf<Line>()
            val course = course()
            course?.let { saved ->
                lines += Line(saved.course.points.map { worldPixel(it.lat, it.lon, z) }, COURSE_RGB, 3)
            }
            lines += Line(trail.map { worldPixel(it.lat, it.lon, z) }, TRAIL_RGB, 3)
            val img = toGColor8(composeView(center, h, W, H, loaded, lines), W, H)
            val v = View(center, h, z, course, key, scrollX, scrollY)
            view = v
            var off = 0
            while (off < img.size) {
                val end = minOf(off + CHUNK, img.size)
                val msg = mutableMapOf<UInt, PebbleDictionaryItem>(
                    Keys.IMG_OFFSET to PebbleDictionaryItem.Int32(off),
                    Keys.IMG_DATA to PebbleDictionaryItem.Bytes(img.copyOfRange(off, end)),
                )
                if (end == img.size) msg.putAll(marker(v, lastFix ?: fix))
                if (!PebbleMessenger.send(context, msg)) {
                    // A lost chunk leaves a stale strip; send a fresh image next fix.
                    view = null
                    return
                }
                off = end
            }
        } finally {
            composing = false
        }
    }

    companion object {
        private const val TAG = "MapSession"
        const val W = 200
        const val H = 228
        // Larger chunks were slower and overflowed the watch's inbox (task 15).
        private const val CHUNK = 4000
        private const val MAX_ACCURACY_M = 30f
        private const val MOVING_SPEED_MPS = 0.5f
        private const val TRAIL_STEP_M = 5.0
        private const val HEADING_STEP = 20.0
        // Recentre before the marker can slide under the corner overlays.
        private const val RECENTER_PX = 40.0
        private const val RETRY_MS = 30_000L
        private const val COURSE_RGB = 0xFF00FF
        private const val TRAIL_RGB = 0x0000FF

        private fun angleDiff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }
    }
}
