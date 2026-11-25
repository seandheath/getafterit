package org.luckyobserver.getafterit.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TrackFilesTest {

    private fun read(name: String, bytes: ByteArray): List<Track> =
        mutableListOf<Track>().also { tracks -> readTracks(name, bytes.inputStream()) { tracks += it } }

    // Shaped like OpenTracks' KML 2.3 export: a marker first, then the track with two segments.
    private val kml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.3" xmlns:opentracks="http://opentracksapp.com/xmlschemas/v1">
        <Document>
        <Placemark><name>Marker</name><styleUrl>#waypoint</styleUrl><Point><coordinates>-70.0,44.0</coordinates></Point></Placemark>
        <Placemark>
          <name>Morning trail</name>
          <opentracks:trackid>3f2a</opentracks:trackid>
          <ExtendedData><Data name="type"><value>Trail run</value></Data><Data name="activityType"><value>trail_running</value></Data></ExtendedData>
          <MultiTrack>
            <Track>
              <when>2024-05-01T07:00:00.000-04:00</when>
              <when>2024-05-01T07:00:01.000-04:00</when>
              <when>2024-05-01T07:00:02.000-04:00</when>
              <coord/>
              <coord>-70.0 44.0 100.5</coord>
              <coord>-70.0 44.0001 102.0</coord>
              <ExtendedData><SchemaData schemaUrl="#schema">
                <SimpleArrayData name="speed"><value/><value>0.2</value><value>3.1</value></SimpleArrayData>
                <SimpleArrayData name="heartrate"><value>120.0</value><value>130.0</value><value/></SimpleArrayData>
                <SimpleArrayData name="elevation_gain"><value/><value>0.5</value><value>1.5</value></SimpleArrayData>
                <SimpleArrayData name="elevation_loss"><value/><value/><value>0.2</value></SimpleArrayData>
              </SchemaData></ExtendedData>
            </Track>
            <Track>
              <when>2024-05-01T07:10:00.000-04:00</when>
              <coord>-70.0 44.0002 103.0</coord>
            </Track>
          </MultiTrack>
        </Placemark>
        </Document></kml>
    """.trimIndent().toByteArray()

    @Test fun readsOpenTracksKml() {
        val t = read("backup.kml", kml).single()
        assertEquals("Morning trail", t.name)
        assertEquals("trail_running", t.activity)
        assertEquals(2, t.segments.size)
        val (stopped, moving) = t.segments[0]
        assertEquals(1714561201000L, stopped.timeMs)
        assertEquals(44.0, stopped.lat, 0.0)
        assertEquals(-70.0, stopped.lon, 0.0)
        assertEquals(100.5, stopped.eleM!!, 0.0)
        assertEquals(130, stopped.hr)
        assertNull("0.2 m/s is stopped", stopped.speedMps)
        assertEquals(3.1, moving.speedMps!!, 0.0)
    }

    @Test fun readsKmlInsideKmz() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use {
                it.putNextEntry(ZipEntry("images/1.jpg"))
                it.write(byteArrayOf(1, 2, 3))
                it.putNextEntry(ZipEntry("doc.kml"))
                it.write(kml)
            }
        }.toByteArray()
        assertEquals("Morning trail", read("OpenTracks-Backup.KMZ", zip).single().name)
    }

    @Test fun ourGpxReadsBackWithTheSameTotals() {
        val segments = listOf(
            (0..60).map { TrackPoint(it * 1000L, 44.0 + it * 3 / 111_195.0, -70.0, 3.0, 100.0 + it / 2.0, 140) },
            (0..10).map { TrackPoint(100_000L + it * 1000L, 44.1, -70.0, null, 130.0, null) },
        )
        val track = Track("Lunch & hills", "running", segments)
        val back = read("run.gpx", RunRecorder.toGpx(track).toByteArray()).single()
        assertEquals("Lunch & hills", back.name)
        val before = summarize("a.gpx", track)
        val after = summarize("a.gpx", back)
        assertEquals(before.distM, after.distM, 0.5)
        assertEquals(before.movingMs, after.movingMs)
        assertEquals(before.gainM, after.gainM, 0.2)
        assertEquals(140, after.maxHr)
        assertEquals(2, back.segments.size)
    }

    @Test fun gpxWithoutSpeedGetsItFromPositions() {
        val gpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>x</name><trkseg>
            <trkpt lat="44.0" lon="-70.0"><time>2024-05-01T11:00:00Z</time></trkpt>
            <trkpt lat="44.00003" lon="-70.0"><time>2024-05-01T11:00:01Z</time></trkpt>
            <trkpt lat="44.00003" lon="-70.0"><time>2024-05-01T11:00:02Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent().toByteArray()
        val points = read("x.gpx", gpx).single().segments.single()
        assertEquals(3.3, points[1].speedMps!!, 0.1)
        assertNull(points[2].speedMps)
    }

    @Test fun summaryWorksClimbOutFromElevationsNotTheFilesGainArrays() {
        val s = summarize("a.gpx", read("backup.kml", kml).single())
        // Elevations 100.5, 102, 103 stay inside the 3 m dead band; the file's own arrays claim 2 m.
        assertEquals(0.0, s.gainM, 0.0)
        assertEquals("the 120 reading had no position", 130, s.avgHr)
        assertEquals(103.0, s.maxEleM!!, 0.0)
        assertEquals("Morning trail", s.title)
    }
}
