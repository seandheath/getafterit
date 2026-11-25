package org.luckyobserver.getafterit.metrics

import org.luckyobserver.getafterit.course.LatLon
import org.luckyobserver.getafterit.course.haversine

/** One recorded GPS fix. [speedMps] is the filtered speed, null when stopped. */
data class TrackPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double?,
    val eleM: Double?,
    val hr: Int?,
)

/**
 * A run's totals, built up point by point.
 *
 * Distance counts only while moving, in steps of at least [MIN_STEP_M] from the
 * last counted point, so a standing runner's position jitter adds nothing.
 * Climb uses a dead-band of [climbStepM]: a change counts once elevation has
 * moved that far from the last counted level, so sensor noise doesn't add up.
 */
class TrackStats(private val climbStepM: Double) {
    var distM = 0.0
        private set
    var movingMs = 0L
        private set
    var gainM = 0.0
        private set
    var lossM = 0.0
        private set

    private var anchor: LatLon? = null
    private var movingSinceMs: Long? = null  // time of the previous point, when it was moving
    private var level: Double? = null

    /** After a pause: nothing between the last point and the next one counts. */
    fun newSegment() {
        anchor = null
        movingSinceMs = null
        level = null
    }

    fun add(p: TrackPoint) {
        val here = LatLon(p.lat, p.lon)
        val from = anchor
        if (from == null) {
            anchor = here
        } else if (p.speedMps != null && haversine(from, here) >= MIN_STEP_M) {
            distM += haversine(from, here)
            anchor = here
        }

        val prev = movingSinceMs
        if (p.speedMps != null && prev != null && p.timeMs - prev <= MAX_GAP_MS) movingMs += p.timeMs - prev
        movingSinceMs = if (p.speedMps != null) p.timeMs else null

        val ele = p.eleM ?: return
        val ref = level
        if (ref == null || ele - ref >= climbStepM || ref - ele >= climbStepM) {
            if (ref != null && ele > ref) gainM += ele - ref
            if (ref != null && ele < ref) lossM += ref - ele
            level = ele
        }
    }

    companion object {
        /** Pace capped at 3600 sec/km (a slow walk); the watch shows "--" at the cap. */
        const val MAX_PACE_SEC_PER_KM: Int = 3600
        private const val MIN_STEP_M = 5.0
        // A longer gap without fixes isn't counted as moving.
        private const val MAX_GAP_MS = 10_000L
    }
}
