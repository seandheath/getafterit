package org.luckyobserver.getafterit.course

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_M = 6371000.0
private const val OFF_COURSE_M = 50.0
// Matching is limited to a window around the last position on the course so an
// out-and-back course is not matched to the wrong leg.
private const val SEARCH_BACK_M = 50.0
private const val SEARCH_AHEAD_M = 1000.0

private fun rad(deg: Double) = deg * PI / 180

fun haversine(a: LatLon, b: LatLon): Double {
    val dLat = rad(b.lat - a.lat)
    val dLon = rad(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}

/**
 * Reads the track, or the route when there is no track. Planners such as
 * OsmAnd save both: the routed track and the few points the user placed;
 * joining them would draw straight lines between the placed points.
 * Throws on a file with fewer than 2 points.
 */
fun parseGpx(input: InputStream): List<LatLon> {
    val track = mutableListOf<LatLon>()
    val route = mutableListOf<LatLon>()
    SAXParserFactory.newInstance().newSAXParser().parse(input, object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            val into = when (qName.substringAfter(':')) {
                "trkpt" -> track
                "rtept" -> route
                else -> return
            }
            val lat = attrs.getValue("lat")?.toDoubleOrNull()
            val lon = attrs.getValue("lon")?.toDoubleOrNull()
            require(lat != null && lon != null) { "GPX point has invalid lat/lon: $qName" }
            into += LatLon(lat, lon)
        }
    })
    val points = if (track.size >= 2) track else route
    require(points.size >= 2) { "GPX has fewer than 2 track or route points" }
    return points
}

data class Progress(val remainingM: Double, val offCourse: Boolean)

class Course(val points: List<LatLon>) {
    private val cum = DoubleArray(points.size).also {
        for (i in 1 until points.size) it[i] = it[i - 1] + haversine(points[i - 1], points[i])
    }
    val totalM: Double = cum.last()
    private var along = 0.0

    private class Hit(val off: Double, val along: Double)

    // Projects p onto segment i in a local flat frame centred on p.
    private fun project(i: Int, p: LatLon): Hit {
        val a = points[i]
        val b = points[i + 1]
        val ky = EARTH_RADIUS_M * PI / 180
        val kx = ky * cos(rad(p.lat))
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * ky
        val dx = (b.lon - a.lon) * kx
        val dy = (b.lat - a.lat) * ky
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
        val cx = ax + t * dx
        val cy = ay + t * dy
        return Hit(sqrt(cx * cx + cy * cy), cum[i] + t * (cum[i + 1] - cum[i]))
    }

    private fun nearest(p: LatLon, minAlong: Double, maxAlong: Double): Hit? {
        var best: Hit? = null
        for (i in 0 until points.size - 1) {
            if (cum[i + 1] < minAlong || cum[i] > maxAlong) continue
            val r = project(i, p)
            if (best == null || r.off < best.off) best = r
        }
        return best
    }

    /** Advances along the course to [p]. While off course, the position along it stays put. */
    fun progress(p: LatLon): Progress {
        var best = nearest(p, along - SEARCH_BACK_M, along + SEARCH_AHEAD_M)
        if (best == null || best.off > OFF_COURSE_M) best = nearest(p, 0.0, Double.MAX_VALUE)!!
        val offCourse = best.off > OFF_COURSE_M
        if (!offCourse) along = best.along
        return Progress(totalM - along, offCourse)
    }
}
