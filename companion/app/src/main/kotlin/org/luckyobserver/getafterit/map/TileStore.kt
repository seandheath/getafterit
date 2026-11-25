package org.luckyobserver.getafterit.map

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.luckyobserver.getafterit.settings.Settings
import org.luckyobserver.getafterit.settings.TILES_OFFLINE

/** Identifies what tiles are drawn from, so a change of source or offline map file redraws. */
fun tileKey(settings: Settings): String =
    if (settings.tileSource == TILES_OFFLINE) "offline-${OfflineMap.version}" else settings.tileUrl

/**
 * Map tiles, cached as files in the app's cache dir. Android limits that dir
 * per app and clears it itself when storage runs low, so the cache needs no
 * size management of its own.
 */
class TileStore(private val context: Context) {

    /**
     * Decoded 256x256 ARGB tile from the source [settings] pick, or null when it
     * can't be had (no signal, or a render failure); the caller draws it grey.
     */
    fun tile(settings: Settings, z: Int, x: Int, y: Int): IntArray? {
        if (settings.tileSource != TILES_OFFLINE) return downloaded(settings.tileUrl, z, x, y)
        return try {
            OfflineMap.render(context, z, x, y)
        } catch (e: IOException) {
            Log.w(TAG, "offline tile $z/$x/$y: ${e.message}")
            null
        }
    }

    private fun downloaded(urlTemplate: String, z: Int, x: Int, y: Int): IntArray? {
        // Cached per tile URL so switching providers never mixes their tiles.
        val provider = Integer.toHexString(urlTemplate.hashCode())
        val file = File(context.cacheDir, "tiles/$provider/$z/$x/$y.png")
        if (!file.exists()) {
            try {
                fetch(urlTemplate, z, x, y, file)
            } catch (e: IOException) {
                // Usually no signal on the trail; the tile is drawn grey.
                Log.w(TAG, "tile $z/$x/$y: ${e.message}")
                return null
            }
        }
        val bitmap = BitmapFactory.decodeFile(file.path)
            ?: throw IOException("tile $z/$x/$y is not a decodable image")
        val pixels = IntArray(TILE * TILE)
        bitmap.getPixels(pixels, 0, TILE, 0, 0, TILE, TILE)
        bitmap.recycle()
        return pixels
    }

    private fun fetch(urlTemplate: String, z: Int, x: Int, y: Int, file: File) {
        val url = urlTemplate.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        val conn = URL(url).openConnection() as HttpURLConnection
        // tile.openstreetmap.org rejects requests without an app-identifying User-Agent.
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode != 200) throw IOException("$url HTTP ${conn.responseCode}")
            file.parentFile!!.mkdirs()
            // Write to a temp file first so an interrupted download never looks cached.
            val tmp = File(file.path + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(file)) throw IOException("could not save $file")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "TileStore"
        private const val USER_AGENT = "GetAfterIt/0.1 (Pebble watch activity tracker)"
    }
}
