package org.luckyobserver.getafterit.metrics

import org.luckyobserver.getafterit.course.LatLon
import org.luckyobserver.getafterit.course.haversine
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * An activity read from a file: its points split at pauses, plus what the file
 * says about it. [activity] is the GPX `<type>` or OpenTracks activity ID
 * (e.g. "running", "hiking"), empty when the file doesn't say.
 *
 * OpenTracks' own per-point gain/loss values are ignored: older exports hold
 * junk there (identical gain and loss arrays with jumps of 90 m in a second),
 * so climb is always worked out from the elevations.
 */
data class Track(
    val name: String,
    val activity: String,
    val segments: List<List<TrackPoint>>,
) {
    val startMs get() = segments.first().first().timeMs
}

/** Reads every track in a GPX, KML or KMZ (by [fileName]'s extension), passing each to [onTrack] as it's parsed. */
fun readTracks(fileName: String, input: InputStream, onTrack: (Track) -> Unit) {
    when (fileName.substringAfterLast('.').lowercase()) {
        "gpx" -> parse(input, GpxHandler(onTrack))
        "kml" -> parse(input, KmlHandler(onTrack))
        "kmz" -> {
            // A KMZ is a zip holding doc.kml and the track's pictures.
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: throw IllegalArgumentException("$fileName has no KML inside")
                if (entry.name.endsWith(".kml", ignoreCase = true)) break
            }
            parse(zip, KmlHandler(onTrack))
        }
        else -> throw IllegalArgumentException("$fileName isn't a GPX, KML or KMZ file")
    }
}

private fun parse(input: InputStream, handler: DefaultHandler) {
    val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    factory.newSAXParser().parse(input, handler)
}

private fun parseTime(text: String): Long =
    OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli()

/** One point as read; [speedMps] is the file's speed, or null when it has none. */
private class RawPoint(val timeMs: Long, val lat: Double, val lon: Double, val speedMps: Double?, val eleM: Double?, val hr: Int?)

/**
 * Turns raw points into [TrackPoint]s, whose speed is null when stopped. Files
 * without speed get it from the distance to the previous point.
 */
private fun withMovement(raw: List<RawPoint>): List<TrackPoint> = raw.mapIndexed { i, p ->
    val speed = p.speedMps ?: raw.getOrNull(i - 1)?.let { prev ->
        val dt = (p.timeMs - prev.timeMs) / 1000.0
        if (dt <= 0) null else haversine(LatLon(prev.lat, prev.lon), LatLon(p.lat, p.lon)) / dt
    }
    TrackPoint(p.timeMs, p.lat, p.lon, speed?.takeIf { it >= PaceFilter.STOPPED_MPS }, p.eleM, p.hr)
}

/** Collects element text; SAX may deliver it in pieces. */
private abstract class TextHandler : DefaultHandler() {
    protected val text = StringBuilder()
    protected val path = ArrayDeque<String>()

    final override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        text.setLength(0)
        start(localName, attrs)
        path.addLast(localName)
    }

    final override fun endElement(uri: String, localName: String, qName: String) {
        path.removeLast()
        end(localName, text.toString().trim())
        text.setLength(0)
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        text.append(ch, start, length)
    }

    protected val parent get() = path.lastOrNull()
    abstract fun start(name: String, attrs: Attributes)
    abstract fun end(name: String, text: String)
}

/** GPX 1.0/1.1, with heart rate and speed from Garmin's TrackPointExtension. */
private class GpxHandler(private val onTrack: (Track) -> Unit) : TextHandler() {
    private var name = ""
    private var activity = ""
    private val segments = mutableListOf<List<TrackPoint>>()
    private val points = mutableListOf<RawPoint>()
    private var lat = 0.0
    private var lon = 0.0
    private var time: Long? = null
    private var ele: Double? = null
    private var hr: Int? = null
    private var speed: Double? = null

    override fun start(name: String, attrs: Attributes) {
        when (name) {
            "trk" -> {
                this.name = ""
                activity = ""
                segments.clear()
            }
            "trkseg" -> points.clear()
            "trkpt" -> {
                lat = attrs.getValue("lat")?.toDoubleOrNull() ?: throw IllegalArgumentException("GPX point without lat")
                lon = attrs.getValue("lon")?.toDoubleOrNull() ?: throw IllegalArgumentException("GPX point without lon")
                time = null
                ele = null
                hr = null
                speed = null
            }
        }
    }

    override fun end(name: String, text: String) {
        when {
            name == "name" && parent == "trk" -> this.name = text
            name == "type" && parent == "trk" -> activity = text
            name == "time" && parent == "trkpt" -> time = parseTime(text)
            name == "ele" && parent == "trkpt" -> ele = text.toDoubleOrNull()
            name == "hr" -> hr = text.toDoubleOrNull()?.toInt()
            name == "speed" -> speed = text.toDoubleOrNull()
            name == "trkpt" -> {
                val t = time ?: throw IllegalArgumentException("GPX track point without a time")
                points += RawPoint(t, lat, lon, speed, ele, hr)
            }
            name == "trkseg" -> if (points.isNotEmpty()) segments += withMovement(points.toList())
            name == "trk" -> if (segments.isNotEmpty()) onTrack(Track(this.name, activity, segments.toList()))
        }
    }
}

/**
 * KML 2.2 (gx:Track) and 2.3 (Track), as OpenTracks writes them: one Placemark
 * per activity, one Track per segment, sensor values in SimpleArrayData.
 */
private class KmlHandler(private val onTrack: (Track) -> Unit) : TextHandler() {
    private var name = ""
    private var activity = ""
    private var dataName: String? = null
    private val segments = mutableListOf<List<TrackPoint>>()
    private val whens = mutableListOf<String>()
    private val coords = mutableListOf<String>()
    private val arrays = mutableMapOf<String, MutableList<String>>()
    private var array: String? = null

    override fun start(name: String, attrs: Attributes) {
        when (name) {
            "Placemark" -> {
                this.name = ""
                activity = ""
                segments.clear()
            }
            "Data" -> dataName = attrs.getValue("name")
            "Track" -> {
                whens.clear()
                coords.clear()
                arrays.clear()
            }
            "SimpleArrayData" -> array = attrs.getValue("name")?.also { arrays[it] = mutableListOf() }
        }
    }

    override fun end(name: String, text: String) {
        when {
            name == "name" && parent == "Placemark" -> this.name = text
            name == "value" && parent == "Data" && dataName == "activityType" -> activity = text
            name == "when" -> whens += text
            name == "coord" -> coords += text
            name == "value" && parent == "SimpleArrayData" -> array?.let { arrays.getValue(it) += text }
            name == "SimpleArrayData" -> array = null
            name == "Track" -> track()
            name == "Placemark" -> if (segments.isNotEmpty()) onTrack(Track(this.name, activity, segments.toList()))
        }
    }

    private fun track() {
        require(whens.size == coords.size) { "KML track has ${whens.size} times but ${coords.size} positions" }
        fun value(name: String, i: Int) = arrays[name]?.getOrNull(i)?.toDoubleOrNull()
        val points = mutableListOf<RawPoint>()
        for (i in whens.indices) {
            // Sensor-only points (no fix yet) have an empty position.
            val c = coords[i].split(' ').filter { it.isNotEmpty() }
            if (c.size < 2) continue
            val hr = value("heartrate", i) ?: value("heart_rate", i)
            points += RawPoint(parseTime(whens[i]), c[1].toDouble(), c[0].toDouble(), value("speed", i), c.getOrNull(2)?.toDoubleOrNull(), hr?.toInt())
        }
        if (points.isNotEmpty()) segments += withMovement(points)
    }
}
