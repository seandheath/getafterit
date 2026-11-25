package org.luckyobserver.getafterit.pebble

import kotlinx.coroutines.flow.MutableStateFlow
import org.luckyobserver.getafterit.course.LatLon

/** The current run's numbers for the phone screen. */
object LiveRun {
    data class Stats(
        val movingS: Long,
        val distM: Long,
        val paceSKm: Int?,
        val gainM: Long,
        val lossM: Long,
        val strapHr: Int?,
        val atMs: Long,
    )

    /** Null until the first update of a run, and after it stops. */
    val stats = MutableStateFlow<Stats?>(null)

    /** Meters to the end of the course; null without a course or a fix. */
    val remainingM = MutableStateFlow<Double?>(null)

    /** Filtered GPS speed (null when stopped) and when it was computed, on the elapsedRealtime clock. */
    data class Speed(val speedMps: Double?, val atMs: Long)

    @Volatile var speed = Speed(null, 0L)

    /** The run's breadcrumb trail, as drawn on the watch map. */
    val trail = MutableStateFlow<List<LatLon>>(emptyList())
}
