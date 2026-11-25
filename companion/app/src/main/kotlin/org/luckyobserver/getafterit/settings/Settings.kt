package org.luckyobserver.getafterit.settings

import android.content.Context
import android.content.SharedPreferences
import org.luckyobserver.getafterit.pebble.Keys
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem

/** Order matches the Stat enum in src/c/main.c. */
val STAT_NAMES = listOf(
    "None", "Distance", "Elapsed time", "Moving time", "Current pace", "Average pace",
    "Heart rate", "Average HR", "Max HR", "HR zone", "Elevation", "Elevation gain",
    "Elevation loss", "Distance to go", "ETA", "Lap time", "Lap pace", "Cadence",
    "Clock", "Battery",
)

val OVERLAY_NAMES = listOf("Map only", "Bottom bar (2 fields)", "Corners and top centre (5 fields)")

const val TILES_OSM = 0
const val TILES_OFFLINE = 1
const val TILES_CUSTOM = 2
val TILE_SOURCE_NAMES = listOf("OpenStreetMap (online)", "Offline map file", "Custom URL (online)")
private const val OSM_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

/**
 * The activities picked before starting; order matches the Mode enum in src/c/main.c.
 * Track ones follow the loaded course, Record ones just record the breadcrumb;
 * Workout is timed on the watch and not recorded.
 */
const val MODE_RUN = 0
const val MODE_RUN_TRACK = 1
const val MODE_RUN_RECORD = 2
const val MODE_HIKE_TRACK = 3
const val MODE_HIKE_RECORD = 4
const val MODE_WORKOUT = 5
val MODE_NAMES = listOf("Run", "Run Track", "Record Run", "Hike Track", "Record Hike", "Workout")
private const val ALL_MODES = (1 shl 6) - 1
/** The activities with a map, in order; each has its own [MapScreens]. */
val MAP_MODES = listOf(MODE_RUN_TRACK, MODE_RUN_RECORD, MODE_HIKE_TRACK, MODE_HIKE_RECORD)

fun showsMap(mode: Int) = mode in MAP_MODES
fun followsCourse(mode: Int) = mode == MODE_RUN_TRACK || mode == MODE_HIKE_TRACK
/** The GPX `<type>` a recording of [mode] is saved with. */
fun activityId(mode: Int) = if (mode == MODE_HIKE_TRACK || mode == MODE_HIKE_RECORD) "hiking" else "running"

/** A map activity's map screens ([MAP_SCREENS] x [MAP_SLOTS]) and its stats page ([STAT_SLOTS]). */
data class MapScreens(val map: List<Int>, val stats: List<Int>)

// Defaults match TRACK_SCREENS and RECORD_SCREENS in src/c/main.c; the fifth field of each screen is the top centre (18 = clock).
private val TRACK_SCREENS = MapScreens(listOf(1, 2, 4, 6, 18, 10, 11, 13, 14, 18, 0, 0, 0, 0, 0), listOf(1, 2, 4, 5, 6, 7))
private val RECORD_SCREENS = MapScreens(listOf(1, 2, 4, 6, 18, 10, 11, 3, 5, 18, 0, 0, 0, 0, 0), listOf(1, 2, 4, 5, 6, 7))

const val MAP_SCREENS = 3
const val STATS_SCREENS = 3
const val MAP_SLOTS = 5  // top left, top right, bottom left, bottom right, top centre
const val STAT_SLOTS = 6

/** Defaults match s_settings in src/c/main.c. */
data class Settings(
    val miles: Boolean = true,
    val backlight: Boolean = false,
    val maxHr: Int = 190,
    val overlay: Int = 2,
    /** One per [MAP_MODES] entry, in that order. */
    val mapScreens: List<MapScreens> = listOf(TRACK_SCREENS, RECORD_SCREENS, TRACK_SCREENS, RECORD_SCREENS),
    val forwardsUp: Boolean = false,
    val tileSource: Int = TILES_OSM,
    val customTileUrl: String = OSM_URL,
    /** Show heart rate from the paired chest strap instead of the watch sensor while the watch app is open. */
    val strapHr: Boolean = false,
    /** Vibrate and show OFF COURSE on the watch when more than 50 m from the course. */
    val offCourseAlert: Boolean = false,
    /** Picked on the Ready screen or the watch; whichever changed it last wins. */
    val mode: Int = MODE_RUN,
    /** Bit per mode: the ones Select on the watch cycles through. */
    val modes: Int = ALL_MODES,
    /** Run's stats screens, [STATS_SCREENS] x [STAT_SLOTS]. */
    val statsSlots: List<Int> = listOf(1, 2, 4, 5, 6, 7, 15, 16, 17, 3, 11, 18, 0, 0, 0, 0, 0, 0),
) {
    fun modeEnabled(m: Int) = modes and (1 shl m) != 0

    /** Where online tiles come from; unused with [TILES_OFFLINE]. */
    val tileUrl: String get() = if (tileSource == TILES_CUSTOM) customTileUrl else OSM_URL

    /** Credit the map's source asks for. */
    val attribution: String get() = when (tileSource) {
        TILES_OFFLINE -> "Map © OpenAndroMaps (CC BY-SA), data © OpenStreetMap contributors"
        TILES_CUSTOM -> "Map data © OpenStreetMap contributors"
        else -> "© OpenStreetMap contributors"
    }

    /** Why these settings can't be saved, or null when they can. */
    fun problem(): String? = when {
        maxHr !in 100..250 -> "Max heart rate must be 100-250."
        modes == 0 -> "Turn on at least one mode for the watch."
        tileSource == TILES_CUSTOM &&
            (!customTileUrl.startsWith("https://") || listOf("{z}", "{x}", "{y}").any { it !in customTileUrl }) ->
            "Tile URL must start with https:// and contain {z}, {x} and {y}."
        else -> null
    }

    /** The watch's settings message (keys 140-150). */
    fun toWatch(): PebbleDictionary = mapOf(
        Keys.SET_MILES to PebbleDictionaryItem.Int32(if (miles) 1 else 0),
        Keys.SET_BACKLIGHT to PebbleDictionaryItem.Int32(if (backlight) 1 else 0),
        Keys.SET_MAX_HR to PebbleDictionaryItem.Int32(maxHr),
        Keys.SET_OVERLAY to PebbleDictionaryItem.Int32(overlay),
        Keys.SET_MAP_SLOTS to bytes(mapScreens.flatMap { it.map + it.stats }),
        Keys.SET_STATS_SLOTS to bytes(statsSlots),
        Keys.SET_MODE to PebbleDictionaryItem.Int32(mode),
        Keys.SET_MODES to PebbleDictionaryItem.Int32(modes),
    )

    private fun bytes(slots: List<Int>) = PebbleDictionaryItem.Bytes(slots.map { it.toByte() }.toByteArray())
}

object SettingsStore {
    private const val PREFS = "getafterit"

    fun get(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Settings()
        return Settings(
            miles = p.getBoolean("miles", d.miles),
            backlight = p.getBoolean("backlight", d.backlight),
            maxHr = p.getInt("max_hr", d.maxHr),
            overlay = p.getInt("overlay", d.overlay),
            // A layout saved with a different number of fields per screen is dropped for the defaults.
            mapScreens = p.slots("map_screens")?.takeIf { it.size == MAP_MODES.size * (MAP_SCREENS * MAP_SLOTS + STAT_SLOTS) }
                ?.chunked(MAP_SCREENS * MAP_SLOTS + STAT_SLOTS)
                ?.map { MapScreens(it.take(MAP_SCREENS * MAP_SLOTS), it.drop(MAP_SCREENS * MAP_SLOTS)) } ?: d.mapScreens,
            forwardsUp = p.getBoolean("forwards_up", d.forwardsUp),
            tileSource = p.getInt("tile_source", d.tileSource),
            customTileUrl = p.getString("custom_tile_url", null) ?: d.customTileUrl,
            strapHr = p.getBoolean("strap_hr", d.strapHr),
            offCourseAlert = p.getBoolean("off_course_alert", d.offCourseAlert),
            mode = p.getInt("mode", d.mode),
            modes = p.getInt("activity_modes", d.modes),
            statsSlots = p.slots("run_slots") ?: d.statsSlots,
        )
    }

    private fun SharedPreferences.slots(key: String) = getString(key, null)?.split(',')?.map(String::toInt)

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("miles", s.miles)
            .putBoolean("backlight", s.backlight)
            .putInt("max_hr", s.maxHr)
            .putInt("overlay", s.overlay)
            .putString("map_screens", s.mapScreens.flatMap { it.map + it.stats }.joinToString(","))
            .putBoolean("forwards_up", s.forwardsUp)
            .putInt("tile_source", s.tileSource)
            .putString("custom_tile_url", s.customTileUrl)
            .putBoolean("strap_hr", s.strapHr)
            .putBoolean("off_course_alert", s.offCourseAlert)
            .putInt("mode", s.mode)
            .putInt("activity_modes", s.modes)
            .putString("run_slots", s.statsSlots.joinToString(","))
            .apply()
    }
}
