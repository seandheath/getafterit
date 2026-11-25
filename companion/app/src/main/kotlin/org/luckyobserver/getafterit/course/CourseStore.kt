package org.luckyobserver.getafterit.course

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * The loaded GPX course, kept in app storage as "name" then "lat,lon" lines,
 * plus a library of every course opened, in the same format, to pick from again.
 */
object CourseStore {
    private const val FILE = "course.txt"
    private const val LIBRARY = "courses"

    class Saved(val name: String, val course: Course)

    @Volatile private var cached: Saved? = null
    @Volatile private var loaded = false

    fun get(context: Context): Saved? {
        if (loaded) return cached
        val file = File(context.filesDir, FILE)
        cached = if (file.exists()) read(file) else null
        loaded = true
        return cached
    }

    private fun read(file: File): Saved {
        val lines = file.readLines()
        return Saved(lines.first(), Course(lines.drop(1).map {
            val (lat, lon) = it.split(',')
            LatLon(lat.toDouble(), lon.toDouble())
        }))
    }

    // Courses are found again by name, so one opened twice replaces its old copy.
    private fun libraryFile(context: Context, name: String) =
        File(File(context.filesDir, LIBRARY).apply { mkdirs() }, name.replace(Regex("[^A-Za-z0-9 ._-]"), "_") + ".txt")

    /** Names of every course opened, newest first. */
    fun library(context: Context): List<String> {
        // A course loaded before the library existed joins it.
        get(context)?.let { current ->
            val copy = libraryFile(context, current.name)
            if (!copy.exists()) File(context.filesDir, FILE).copyTo(copy)
        }
        return File(context.filesDir, LIBRARY).listFiles()!!
            .sortedByDescending { it.lastModified() }
            .map { it.useLines { lines -> lines.first() } }
    }

    /** Makes the library course [name] the course. */
    fun pick(context: Context, name: String) {
        val copy = libraryFile(context, name)
        copy.copyTo(File(context.filesDir, FILE), overwrite = true)
        cached = read(copy)
        loaded = true
    }

    /** Parses the GPX at [uri] and makes it the course. Throws if it isn't a usable GPX. */
    fun load(context: Context, uri: Uri) {
        val points = context.contentResolver.openInputStream(uri)!!.use { parseGpx(it) }
        val name = displayName(context, uri)
        val text = (listOf(name) + points.map { "${it.lat},${it.lon}" }).joinToString("\n")
        File(context.filesDir, FILE).writeText(text)
        libraryFile(context, name).writeText(text)
        cached = Saved(name, Course(points))
        loaded = true
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        cached = null
        loaded = true
    }

    fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.removeSuffix(".gpx")
            ?: uri.lastPathSegment
            ?: "Course"
}
