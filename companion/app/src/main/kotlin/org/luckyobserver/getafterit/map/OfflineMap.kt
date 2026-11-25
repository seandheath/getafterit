package org.luckyobserver.getafterit.map

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.labels.MapDataStoreLabelStore
import org.mapsforge.map.layer.renderer.DirectRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.ExternalRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A region map (Mapsforge .map, e.g. from OpenAndroMaps) and optional render
 * style the user downloaded and picked, kept in app storage and rendered into
 * 256 px tiles on the phone. OpenAndroMaps forbids apps from linking to or
 * bundling its files, so the user always fetches them in a browser.
 */
object OfflineMap {

    /** Bumped on every import so drawn maps know to redraw. */
    @Volatile var version = 0
        private set

    private var renderer: Renderer? = null
    private var rendererVersion = -1

    // Rendering is ~0.5 s a tile and every redraw needs the same few tiles again.
    // 32 tiles of 256 KB each is 8 MB.
    private val rendered = LruCache<String, IntArray>(32)

    private fun dir(context: Context) = File(context.filesDir, "offline")
    private fun mapFile(context: Context) = File(dir(context), "map.map")
    private fun themeDir(context: Context) = File(dir(context), "theme")

    /** Display name of the imported map, or null when there is none. */
    fun mapName(context: Context): String? =
        File(dir(context), "map.name").takeIf { mapFile(context).exists() && it.exists() }?.readText()

    /** Display name of the imported style, or null for Mapsforge's built-in one. */
    fun themeName(context: Context): String? =
        File(dir(context), "theme.name").takeIf { themeXml(context) != null && it.exists() }?.readText()

    /** Copies a picked .map, or the .map inside a picked .zip, into app storage. */
    @Synchronized
    fun importMap(context: Context, uri: Uri) {
        val name = displayName(context, uri)
        val target = mapFile(context)
        target.parentFile!!.mkdirs()
        val tmp = File(target.path + ".part")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            if (name.endsWith(".zip", ignoreCase = true)) {
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: throw IllegalArgumentException("$name contains no .map file")
                        if (!entry.isDirectory && entry.name.endsWith(".map", ignoreCase = true)) break
                    }
                    tmp.outputStream().use { zip.copyTo(it) }
                }
            } else {
                require(name.endsWith(".map", ignoreCase = true)) { "Pick a .map file or the .zip it came in" }
                tmp.outputStream().use { input.copyTo(it) }
            }
        }
        // Opening it checks it really is a Mapsforge map before replacing the old one.
        MapFile(tmp).close()
        if (!tmp.renameTo(target)) throw IOException("could not save $target")
        File(dir(context), "map.name").writeText(name.substringBeforeLast('.'))
        version++
    }

    /** Extracts a picked style .zip (e.g. Elevate) into app storage. */
    @Synchronized
    fun importTheme(context: Context, uri: Uri) {
        val name = displayName(context, uri)
        require(name.endsWith(".zip", ignoreCase = true)) { "Pick the style's .zip file" }
        val target = themeDir(context)
        target.deleteRecursively()
        target.mkdirs()
        context.contentResolver.openInputStream(uri)!!.use { unzip(it, target) }
        requireNotNull(themeXml(context)) { "$name contains no style .xml" }
        File(dir(context), "theme.name").writeText(name.substringBeforeLast('.'))
        version++
    }

    @Synchronized
    fun clearTheme(context: Context) {
        themeDir(context).deleteRecursively()
        version++
    }

    /** The style's main .xml: the shallowest one in the extracted zip. */
    private fun themeXml(context: Context): File? =
        themeDir(context).walkTopDown()
            .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
            .minByOrNull { it.path.count { c -> c == '/' } }

    private fun unzip(input: InputStream, target: File) {
        val root = target.canonicalPath + File.separator
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(target, entry.name)
                // A crafted entry name like "../x" must not escape the folder.
                require(out.canonicalPath.startsWith(root)) { "Bad path in zip: ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile!!.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    /**
     * Renders tile [z]/[x]/[y] as 256x256 ARGB pixels. One at a time: Mapsforge
     * warns that concurrent reads can corrupt its map file state.
     */
    @Synchronized
    fun render(context: Context, z: Int, x: Int, y: Int): IntArray {
        rendered.get("$version/$z/$x/$y")?.let { return it }
        if (renderer == null || rendererVersion != version) {
            rendered.evictAll()
            renderer?.close()
            AndroidGraphicFactory.createInstance(context.applicationContext)
            val theme: XmlRenderTheme = themeXml(context)?.let { ExternalRenderTheme(it) } ?: MapsforgeThemes.DEFAULT
            renderer = Renderer(mapFile(context), theme)
            rendererVersion = version
        }
        return renderer!!.render(z, x, y).also { rendered.put("$version/$z/$x/$y", it) }
    }

    private class Renderer(file: File, theme: XmlRenderTheme) {
        private val mapFile = MapFile(file, "en")
        private val displayModel = DisplayModel().apply { setFixedTileSize(TILE) }
        private val themeFuture = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, theme, displayModel)
            .also { Thread(it).start() }
        private val renderer = DirectRenderer(
            mapFile, AndroidGraphicFactory.INSTANCE,
            MapDataStoreLabelStore(mapFile, themeFuture, 1f, displayModel, AndroidGraphicFactory.INSTANCE),
            true, false, null,
        )

        fun render(z: Int, x: Int, y: Int): IntArray {
            val job = RendererJob(Tile(x, y, z.toByte(), TILE), mapFile, themeFuture, displayModel, 1f, false, false)
            val tile = renderer.executeJob(job) ?: throw IOException("Mapsforge could not render $z/$x/$y")
            val pixels = IntArray(TILE * TILE)
            AndroidGraphicFactory.getBitmap(tile).getPixels(pixels, 0, TILE, 0, 0, TILE, TILE)
            tile.decrementRefCount()
            return pixels
        }

        fun close() {
            themeFuture.decrementRefCount()
            mapFile.close()
        }
    }

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "map"
}
