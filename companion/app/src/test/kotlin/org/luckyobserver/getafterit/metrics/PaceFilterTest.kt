package org.luckyobserver.getafterit.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceFilterTest {

    /** Feeds one fix a second starting at second [from]; returns the last output. */
    private fun PaceFilter.feed(speeds: List<Float>, from: Int = 1, accuracy: Float = 5f): Double? {
        var out: Double? = null
        speeds.forEachIndexed { i, v -> out = onFix((from + i) * 1000L, v, accuracy) }
        return out
    }

    @Test fun steadySpeedPassesThrough() {
        assertEquals(3.0, PaceFilter().feed(List(30) { 3f })!!, 1e-6)
    }

    @Test fun singleSpikeIsRemovedByTheMedian() {
        val f = PaceFilter()
        f.feed(List(10) { 3f })
        assertEquals(3.0, f.feed(listOf(6.5f, 3f), from = 11)!!, 1e-6)
    }

    @Test fun jitterIsDamped() {
        // Alternating 2.5 / 3.5 m/s around 3: the output should stay well inside that band.
        val out = PaceFilter().feed(List(60) { if (it % 2 == 0) 2.5f else 3.5f })!!
        assertTrue("got $out", out in 2.8..3.2)
    }

    @Test fun followsARealChange() {
        val f = PaceFilter()
        f.feed(List(30) { 2.5f })
        val after10s = f.feed(List(10) { 3.5f }, from = 31)!!
        assertTrue("got $after10s", after10s > 3.0)
    }

    @Test fun stopsBelowWalkingAndResumesAtOnce() {
        val f = PaceFilter()
        f.feed(List(10) { 3f })
        assertNull(f.feed(List(3) { 0.2f }, from = 11))
        // The median needs two of three fresh values above the stop line, then no slow ramp.
        assertEquals(3.2, f.feed(listOf(3.2f, 3.2f), from = 14)!!, 1e-6)
    }

    @Test fun ignoresInaccurateAndImplausibleFixes() {
        val f = PaceFilter()
        f.feed(List(5) { 3f })
        assertEquals(3.0, f.onFix(6000, 5f, 50f)!!, 1e-6)
        assertEquals(3.0, f.onFix(7000, 9f, 5f)!!, 1e-6)
        assertEquals(3.0, f.onFix(8000, null, 5f)!!, 1e-6)
    }
}
