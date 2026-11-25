package org.luckyobserver.getafterit.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStatsTest {

    // About 1.11 m of latitude per 1e-5 degree.
    private fun point(s: Int, northM: Double, speed: Double?, ele: Double? = null) =
        TrackPoint(s * 1000L, 44.0 + northM / 111_195.0, -70.0, speed, ele, null)

    @Test fun movingAddsDistanceAndTime() {
        val t = TrackStats(3.0)
        for (s in 0..100) t.add(point(s, s * 3.0, 3.0))
        assertEquals(300.0, t.distM, 6.0)
        assertEquals(100_000L, t.movingMs)
    }

    @Test fun jitterWhileStoppedAddsNothing() {
        val t = TrackStats(3.0)
        for (s in 0..100) t.add(point(s, if (s % 2 == 0) 0.0 else 8.0, null))
        assertEquals(0.0, t.distM, 0.0)
        assertEquals(0L, t.movingMs)
    }

    @Test fun gapWithoutFixesIsNotMovingTime() {
        val t = TrackStats(3.0)
        t.add(point(0, 0.0, 3.0))
        t.add(point(1, 3.0, 3.0))
        t.add(point(60, 180.0, 3.0))
        assertEquals(1000L, t.movingMs)
        assertEquals(180.0, t.distM, 2.0)
    }

    @Test fun elevationNoiseInsideTheDeadBandIsIgnored() {
        val t = TrackStats(3.0)
        for (s in 0..100) t.add(point(s, 0.0, null, 100.0 + if (s % 2 == 0) 0.0 else 2.5))
        assertEquals(0.0, t.gainM, 0.0)
        assertEquals(0.0, t.lossM, 0.0)
    }

    @Test fun climbAndDescentCount() {
        val t = TrackStats(3.0)
        for (s in 0..50) t.add(point(s, 0.0, null, 100.0 + s))        // up 50 m
        for (s in 51..80) t.add(point(s, 0.0, null, 150.0 - (s - 50))) // down 30 m
        assertEquals(48.0, t.gainM, 3.0)
        assertEquals(30.0, t.lossM, 3.0)
    }

    @Test fun nothingCountsAcrossAPause() {
        val t = TrackStats(3.0)
        t.add(point(0, 0.0, 3.0, 100.0))
        t.add(point(1, 6.0, 6.0, 100.0))
        t.newSegment()
        // Resumed 200 m on and 20 m higher, 5 s later: none of that is the run's.
        t.add(point(6, 206.0, 6.0, 120.0))
        t.add(point(7, 212.0, 6.0, 120.0))
        assertEquals(12.0, t.distM, 1.0)
        assertEquals(2000L, t.movingMs)
        assertEquals(0.0, t.gainM, 0.0)
    }

    @Test fun gpxCarriesPositionsElevationTimeAndHeartRate() {
        val gpx = RunRecorder.toGpx(Track(
            "2026-11-25 0730", "running",
            listOf(
                listOf(TrackPoint(0L, 44.5, -70.25, 3.0, 101.26, 142)),
                listOf(TrackPoint(1000L, 44.6, -70.26, null, null, null)),
            ),
        ))
        assertTrue(gpx, """<trkpt lat="44.5" lon="-70.25"><ele>101.3</ele><time>1970-01-01T00:00:00Z</time>""" in gpx)
        assertTrue(gpx, "<gpxtpx:hr>142</gpxtpx:hr><gpxtpx:speed>3.00</gpxtpx:speed>" in gpx)
        assertTrue(gpx, """<trkpt lat="44.6" lon="-70.26"><time>1970-01-01T00:00:01Z</time>""" in gpx)
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
    }
}
