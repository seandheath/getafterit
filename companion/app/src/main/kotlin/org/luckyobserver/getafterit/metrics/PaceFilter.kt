package org.luckyobserver.getafterit.metrics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * Current running speed from the GPS's Doppler speed, one fix at a time.
 *
 * Differencing positions is jittery: a short window spans a few points,
 * each carrying full position error. Doppler speed is far steadier. Here it is filtered as:
 * reject bad fixes, median of 3 (removes spikes), then a one-euro low-pass
 * (Casiez et al., CHI 2012) whose cutoff rises with the rate of change, so
 * it is smooth while steady and quick on a surge or slowdown. Smoothing is
 * done on speed and only converted to pace by the caller, since averaging
 * pace is biased and blows up near a stop.
 *
 * Starting parameters from research, to be tuned in the field: [fcMin] sets
 * steady-state smoothing (0.02 Hz is a time constant of about 8 s), [beta]
 * how fast it follows changes.
 */
class PaceFilter(
    private val fcMin: Double = 0.02,
    private val beta: Double = 0.1,
    private val dCut: Double = 0.2,
) {
    private val recent = ArrayDeque<Double>()
    private var speed: Double? = null
    private var dSpeed = 0.0
    private var lastMs = 0L

    /**
     * Feeds one fix; returns the filtered speed in m/s, or null when stopped.
     * Fixes that can't be trusted leave the current value unchanged.
     */
    fun onFix(timeMs: Long, speedMps: Float?, accuracyM: Float): Double? {
        if (speedMps == null || accuracyM > MAX_ACCURACY_M || speedMps > MAX_SPEED_MPS || timeMs <= lastMs) {
            return speed
        }
        recent.addLast(speedMps.toDouble())
        if (recent.size > MEDIAN_OF) recent.removeFirst()
        val x = recent.sorted()[recent.size / 2]
        if (x < STOPPED_MPS) {
            // Stopped: forget the history so the first fix after resuming shows straight away.
            speed = null
            dSpeed = 0.0
            lastMs = timeMs
            return null
        }
        val prev = speed
        val dt = (timeMs - lastMs) / 1000.0
        lastMs = timeMs
        if (prev == null) {
            speed = x
            return x
        }
        dSpeed += alpha(dt, dCut) * ((x - prev) / dt - dSpeed)
        var cutoff = fcMin + beta * abs(dSpeed)
        if (accuracyM > TRUST_LESS_ACCURACY_M) cutoff /= 2
        return (prev + alpha(dt, cutoff) * (x - prev)).also { speed = it }
    }

    private fun alpha(dt: Double, cutoffHz: Double) = 1 - exp(-2 * PI * cutoffHz * dt)

    companion object {
        private const val MEDIAN_OF = 3
        private const val MAX_ACCURACY_M = 30f
        private const val TRUST_LESS_ACCURACY_M = 15f
        private const val MAX_SPEED_MPS = 7f   // about 2:23/km, beyond trail running
        const val STOPPED_MPS = 0.5           // about 33 min/km
    }
}
