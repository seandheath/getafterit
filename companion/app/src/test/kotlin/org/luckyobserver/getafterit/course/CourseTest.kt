package org.luckyobserver.getafterit.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseTest {

    private fun gpx(text: String) = parseGpx(text.byteInputStream())

    // Points due north of 45,-122; about 111.2 m per 0.001 degree of latitude.
    private fun line(vararg latOffsets: Double) = Course(latOffsets.map { LatLon(45 + it, -122.0) })

    @Test fun haversineMatchesOneDegreeOfLatitude() {
        assertEquals(111195.0, haversine(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 1.0)
    }

    @Test fun parsesTrackPoints() {
        val points = gpx("""<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
            <trkpt lat="45.1" lon="-122.2"><ele>100.5</ele></trkpt>
            <trkpt lon='-122.3' lat='45.2'/>
            </trkseg></trk></gpx>""")
        assertEquals(listOf(LatLon(45.1, -122.2), LatLon(45.2, -122.3)), points)
    }

    @Test fun parsesRoutePoints() {
        assertEquals(2, gpx("""<gpx><rte><rtept lat="1" lon="2"/><rtept lat="3" lon="4"/></rte></gpx>""").size)
    }

    @Test fun prefersTrackOverPlannedRoutePoints() {
        // OsmAnd's planned routes carry the placed points as <rte> and the routed line as <trk>.
        val points = gpx("""<gpx><rte><rtept lat="1" lon="1"/><rtept lat="2" lon="2"/></rte>
            <trk><trkseg><trkpt lat="1" lon="1"/><trkpt lat="1.5" lon="1.4"/><trkpt lat="2" lon="2"/></trkseg></trk></gpx>""")
        assertEquals(listOf(LatLon(1.0, 1.0), LatLon(1.5, 1.4), LatLon(2.0, 2.0)), points)
    }

    @Test fun rejectsFileWithoutPoints() {
        val e = assertThrows(IllegalArgumentException::class.java) { gpx("""<gpx><wpt lat="1" lon="2"/></gpx>""") }
        assertTrue(e.message!!.contains("fewer than 2"))
    }

    @Test fun rejectsPointWithoutLat() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            gpx("""<gpx><trkpt lon="2"/><trkpt lat="1" lon="2"/></gpx>""")
        }
        assertTrue(e.message!!.contains("invalid lat/lon"))
    }

    @Test fun progressAlongStraightCourse() {
        val course = line(0.0, 0.005, 0.01)
        val p = course.progress(LatLon(45.005, -122.0))
        assertEquals(1112.0, course.totalM, 1.0)
        assertEquals(556.0, p.remainingM, 1.0)
        assertFalse(p.offCourse)
    }

    @Test fun offCourseBeyond50mKeepsProgress() {
        val course = line(0.0, 0.005, 0.01)
        course.progress(LatLon(45.003, -122.0))
        // 0.001 degree of longitude at 45N is about 79 m.
        val p = course.progress(LatLon(45.006, -122.001))
        assertTrue(p.offCourse)
        assertEquals(course.totalM - 333.6, p.remainingM, 1.0)
    }

    @Test fun outAndBackMatchesTheLegTheRunnerIsOn() {
        val course = line(0.0, 0.0025, 0.005, 0.0025, 0.0)
        val out = course.progress(LatLon(45.001, -122.0))
        assertEquals(course.totalM - 111.2, out.remainingM, 1.0)
        for (lat in listOf(0.002, 0.003, 0.004, 0.005, 0.004, 0.003, 0.002)) course.progress(LatLon(45 + lat, -122.0))
        val back = course.progress(LatLon(45.001, -122.0))
        assertEquals(111.2, back.remainingM, 1.0)
    }
}
