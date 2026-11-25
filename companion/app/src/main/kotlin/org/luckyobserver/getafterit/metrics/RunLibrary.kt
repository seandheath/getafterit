package org.luckyobserver.getafterit.metrics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.time.Instant
import kotlin.math.roundToInt

/** What the history screens show for one run, worked out once from its points. */
data class RunSummary(
    val file: String,
    val title: String,
    val activity: String,
    val startMs: Long,
    val elapsedMs: Long,
    val movingMs: Long,
    val distM: Double,
    val gainM: Double,
    val lossM: Double,
    val maxEleM: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
)

// Recorded runs' elevation is barometric (see PebbleListenerService).
private const val CLIMB_STEP_M = 3.0

/** Totals for [track], worked out from its points. */
fun summarize(file: String, track: Track): RunSummary {
    val stats = TrackStats(CLIMB_STEP_M)
    track.segments.forEachIndexed { i, segment ->
        if (i > 0) stats.newSegment()
        segment.forEach(stats::add)
    }
    val points = track.segments.flatten()
    val hrs = points.mapNotNull { it.hr }
    return RunSummary(
        file = file,
        title = track.name.ifEmpty { file.removeSuffix(".gpx") },
        activity = track.activity,
        startMs = points.first().timeMs,
        elapsedMs = points.last().timeMs - points.first().timeMs,
        movingMs = stats.movingMs,
        distM = stats.distM,
        gainM = stats.gainM,
        lossM = stats.lossM,
        maxEleM = points.mapNotNull { it.eleM }.maxOrNull(),
        avgHr = if (hrs.isEmpty()) null else hrs.average().roundToInt(),
        maxHr = hrs.maxOrNull(),
    )
}

/**
 * Every recorded and imported activity, as GPX files in [dir] plus an index of
 * their summaries so the history screens needn't parse every file. A file the
 * index doesn't know (a run just recorded) is summarized on the next [all].
 */
object RunLibrary {
    private const val INDEX = "index.json"

    fun dir(context: Context) = File(context.filesDir, "runs")

    /** Every saved activity, newest first. */
    @Synchronized fun all(context: Context): List<RunSummary> {
        val dir = dir(context).apply { mkdirs() }
        val indexed = readIndex(dir).associateBy { it.file }
        val runs = dir.listFiles { f -> f.extension == "gpx" }!!.map { indexed[it.name] ?: summarizeFile(it) }
        if (runs.map { it.file }.toSet() != indexed.keys) writeIndex(dir, runs)
        return runs.sortedByDescending { it.startMs }
    }

    class ImportResult(val added: Int, val skipped: Int)

    /**
     * Adds every activity in the GPX, KML or KMZ [input]. One starting at the
     * same moment as a saved activity is already here and is skipped, so
     * importing a backup again is harmless.
     */
    @Synchronized fun import(context: Context, fileName: String, input: InputStream): ImportResult {
        val dir = dir(context)
        val runs = all(context).toMutableList()
        val starts = runs.map { it.startMs }.toMutableSet()
        var added = 0
        var skipped = 0
        readTracks(fileName, input) { track ->
            if (!starts.add(track.startMs)) {
                skipped++
                return@readTracks
            }
            val file = uniqueFile(dir, RunRecorder.NAME_FORMAT.format(Instant.ofEpochMilli(track.startMs)))
            file.writeText(RunRecorder.toGpx(track))
            runs += summarize(file.name, track)
            added++
        }
        writeIndex(dir, runs)
        return ImportResult(added, skipped)
    }

    private fun uniqueFile(dir: File, name: String): File =
        generateSequence(1) { it + 1 }
            .map { n -> File(dir, if (n == 1) "$name.gpx" else "$name ($n).gpx") }
            .first { !it.exists() }

    private fun summarizeFile(file: File): RunSummary {
        var summary: RunSummary? = null
        file.inputStream().use { input -> readTracks(file.name, input) { if (summary == null) summary = summarize(file.name, it) } }
        return summary ?: throw IllegalStateException("${file.name} has no track")
    }

    private fun readIndex(dir: File): List<RunSummary> {
        val file = File(dir, INDEX)
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            RunSummary(
                file = o.getString("file"),
                title = o.getString("title"),
                activity = o.getString("activity"),
                startMs = o.getLong("startMs"),
                elapsedMs = o.getLong("elapsedMs"),
                movingMs = o.getLong("movingMs"),
                distM = o.getDouble("distM"),
                gainM = o.getDouble("gainM"),
                lossM = o.getDouble("lossM"),
                maxEleM = if (o.has("maxEleM")) o.getDouble("maxEleM") else null,
                avgHr = if (o.has("avgHr")) o.getInt("avgHr") else null,
                maxHr = if (o.has("maxHr")) o.getInt("maxHr") else null,
            )
        }
    }

    private fun writeIndex(dir: File, runs: List<RunSummary>) {
        val array = JSONArray()
        for (r in runs) {
            array.put(JSONObject().apply {
                put("file", r.file)
                put("title", r.title)
                put("activity", r.activity)
                put("startMs", r.startMs)
                put("elapsedMs", r.elapsedMs)
                put("movingMs", r.movingMs)
                put("distM", r.distM)
                put("gainM", r.gainM)
                put("lossM", r.lossM)
                r.maxEleM?.let { put("maxEleM", it) }
                r.avgHr?.let { put("avgHr", it) }
                r.maxHr?.let { put("maxHr", it) }
            })
        }
        // Written aside and renamed, so a crash mid-write can't leave half an index.
        val tmp = File(dir, "$INDEX.tmp")
        tmp.writeText(array.toString())
        check(tmp.renameTo(File(dir, INDEX))) { "couldn't replace the run index" }
    }
}
