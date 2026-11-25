package org.luckyobserver.getafterit.metrics

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Records the run in progress, appending each point to a file so a restarted
 * app picks up where it left off ([resume] replays it), and saves finished
 * runs as GPX. A blank line in the file marks a pause.
 */
class RunRecorder(context: Context, private val climbStepM: Double) {
    private val current = File(context.filesDir, CURRENT)
    private var stats = TrackStats(climbStepM)
    private var lastEle: Double? = null
    private var baroOffset: Double? = null

    /** A snapshot of the totals so far. */
    data class Totals(val distM: Double, val movingMs: Long, val gainM: Double, val lossM: Double)

    @Synchronized fun totals() = Totals(stats.distM, stats.movingMs, stats.gainM, stats.lossM)

    @Synchronized fun start() {
        current.delete()
        reset()
    }

    @Synchronized fun resume() {
        reset()
        if (!current.exists()) return
        current.forEachLine { line ->
            if (line.isEmpty()) {
                stats.newSegment()
                return@forEachLine
            }
            val p = parse(line)
            stats.add(p)
            lastEle = p.eleM ?: lastEle
        }
    }

    /** Ends the current track segment; the next point starts a new one. */
    @Synchronized fun pause() {
        current.appendText("\n")
        stats.newSegment()
    }

    /**
     * Records a fix. Elevation comes from the barometer when the phone has one,
     * levelled to the GPS altitude (or, after a restart, to the last recorded
     * elevation) since standard-atmosphere altitude is off by the weather.
     */
    @Synchronized fun add(timeMs: Long, lat: Double, lon: Double, speedMps: Double?, gpsAltM: Double?, baroAltM: Double?, hr: Int?) {
        val ele = if (baroAltM == null) gpsAltM else {
            val offset = baroOffset ?: ((lastEle ?: gpsAltM ?: baroAltM) - baroAltM).also { baroOffset = it }
            baroAltM + offset
        }
        val p = TrackPoint(timeMs, lat, lon, speedMps, ele, hr)
        current.appendText(format(p) + "\n")
        stats.add(p)
        lastEle = ele ?: lastEle
    }

    /** Saves the run as GPX of [activity] (a GPX `<type>` such as "running") and returns it; null when no point was recorded. */
    @Synchronized fun finish(dir: File, activity: String): File? {
        if (!current.exists()) return null
        val segments = current.readText().split("\n\n")
            .map { seg -> seg.lines().filter { it.isNotEmpty() }.map(::parse) }
            .filter { it.isNotEmpty() }
        current.delete()
        if (segments.isEmpty()) return null
        val name = NAME_FORMAT.format(Instant.ofEpochMilli(segments.first().first().timeMs))
        dir.mkdirs()
        return File(dir, "$name.gpx").apply { writeText(toGpx(Track(name, activity, segments))) }
    }

    private fun reset() {
        stats = TrackStats(climbStepM)
        lastEle = null
        baroOffset = null
    }

    companion object {
        private const val CURRENT = "run.csv"
        val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm").withZone(ZoneId.systemDefault())

        private fun format(p: TrackPoint) =
            listOf(p.timeMs, p.lat, p.lon, p.speedMps ?: "", p.eleM ?: "", p.hr ?: "").joinToString(",")

        private fun parse(line: String): TrackPoint {
            val f = line.split(',')
            return TrackPoint(
                f[0].toLong(), f[1].toDouble(), f[2].toDouble(),
                f[3].toDoubleOrNull(), f[4].toDoubleOrNull(), f[5].toIntOrNull(),
            )
        }

        /**
         * GPX 1.1, one track segment per stretch between pauses, with heart rate
         * and speed in Garmin's TrackPointExtension, which Strava and others read.
         * Speed is 0 while stopped, so reading the file back finds the same moving time.
         */
        fun toGpx(track: Track): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<gpx version="1.1" creator="Get After It" xmlns="http://www.topografix.com/GPX/1/1" """)
            append("""xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2">""").append('\n')
            append("<trk><name>${escape(track.name)}</name><type>${escape(track.activity)}</type>\n")
            for (points in track.segments) {
                append("<trkseg>\n")
                for (p in points) {
                    append("""<trkpt lat="${p.lat}" lon="${p.lon}">""")
                    p.eleM?.let { append("<ele>${"%.1f".format(Locale.ROOT, it)}</ele>") }
                    append("<time>${Instant.ofEpochMilli(p.timeMs)}</time>")
                    append("<extensions><gpxtpx:TrackPointExtension>")
                    p.hr?.let { append("<gpxtpx:hr>$it</gpxtpx:hr>") }
                    append("<gpxtpx:speed>${"%.2f".format(Locale.ROOT, p.speedMps ?: 0.0)}</gpxtpx:speed>")
                    append("</gpxtpx:TrackPointExtension></extensions>")
                    append("</trkpt>\n")
                }
                append("</trkseg>\n")
            }
            append("</trk></gpx>\n")
        }

        private fun escape(s: String) =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
