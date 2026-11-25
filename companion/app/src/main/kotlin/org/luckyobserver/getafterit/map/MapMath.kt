package org.luckyobserver.getafterit.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

const val TILE = 256

data class World(val x: Double, val y: Double)
data class Screen(val u: Double, val v: Double)
data class TileXY(val x: Int, val y: Int)

/** A polyline in world pixels drawn over the map. */
class Line(val points: List<World>, val rgb: Int, val width: Int)

private val MISSING_RGB = 0xC8C8C8
private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

/** Web Mercator pixel coordinates at zoom [z]. */
fun worldPixel(lat: Double, lon: Double, z: Int): World {
    val size = TILE * 2.0.pow(z)
    val s = sin(lat * PI / 180)
    return World((lon + 180) / 360 * size, (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * size)
}

/** Screen offset from the view centre → world offset, for a view rotated so [heading] points up. */
fun screenToWorld(du: Double, dv: Double, heading: Double): World {
    val h = heading * PI / 180
    return World(du * cos(h) - dv * sin(h), du * sin(h) + dv * cos(h))
}

fun worldToScreen(dx: Double, dy: Double, heading: Double): Screen {
    val h = heading * PI / 180
    return Screen(dx * cos(h) + dy * sin(h), -dx * sin(h) + dy * cos(h))
}

/** Tiles covering a [w] x [h] view centred on [center]. */
fun viewTiles(center: World, heading: Double, w: Int, h: Int): List<TileXY> {
    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for ((cu, cv) in listOf(-w / 2.0 to -h / 2.0, w / 2.0 to -h / 2.0, -w / 2.0 to h / 2.0, w / 2.0 to h / 2.0)) {
        val p = screenToWorld(cu, cv, heading)
        minX = min(minX, center.x + p.x); maxX = max(maxX, center.x + p.x)
        minY = min(minY, center.y + p.y); maxY = max(maxY, center.y + p.y)
    }
    val tiles = mutableListOf<TileXY>()
    for (ty in floor(minY / TILE).toInt()..floor(maxY / TILE).toInt()) {
        for (tx in floor(minX / TILE).toInt()..floor(maxX / TILE).toInt()) tiles += TileXY(tx, ty)
    }
    return tiles
}

/**
 * Renders the view as 0xRRGGBB pixels. [tiles] holds decoded tiles as 256x256
 * ARGB arrays; missing tiles are grey.
 */
fun composeView(
    center: World, heading: Double, w: Int, h: Int,
    tiles: Map<TileXY, IntArray>, lines: List<Line>,
): IntArray {
    val rgb = IntArray(w * h)
    for (v in 0 until h) {
        for (u in 0 until w) {
            val p = screenToWorld(u - w / 2.0, v - h / 2.0, heading)
            val wx = floor(center.x + p.x).toInt()
            val wy = floor(center.y + p.y).toInt()
            val tile = tiles[TileXY(Math.floorDiv(wx, TILE), Math.floorDiv(wy, TILE))]
            rgb[v * w + u] = if (tile != null) {
                tile[Math.floorMod(wy, TILE) * TILE + Math.floorMod(wx, TILE)] and 0xFFFFFF
            } else {
                MISSING_RGB
            }
        }
    }
    for (line in lines) {
        val screen = line.points.map {
            val s = worldToScreen(it.x - center.x, it.y - center.y, heading)
            Screen(s.u + w / 2.0, s.v + h / 2.0)
        }
        for (i in 1 until screen.size) drawSegment(rgb, w, h, screen[i - 1], screen[i], line.rgb, line.width)
    }
    return rgb
}

private fun drawSegment(rgb: IntArray, w: Int, h: Int, a: Screen, b: Screen, color: Int, width: Int) {
    // Skip segments entirely off screen so long courses stay cheap.
    if (max(a.u, b.u) < -width || min(a.u, b.u) > w + width ||
        max(a.v, b.v) < -width || min(a.v, b.v) > h + width) return
    val len = max(abs(b.u - a.u), abs(b.v - a.v))
    val steps = max(1, ceil(len * 2).toInt())
    val half = width / 2
    for (s in 0..steps) {
        val cu = (a.u + (b.u - a.u) * s / steps).roundToInt()
        val cv = (a.v + (b.v - a.v) * s / steps).roundToInt()
        for (y in cv - half until cv - half + width) {
            if (y < 0 || y >= h) continue
            for (x in cu - half until cu - half + width) {
                if (x < 0 || x >= w) continue
                rgb[y * w + x] = color
            }
        }
    }
}

/** 0xRRGGBB → Pebble GColor8 bytes (0b11RRGGBB) with 4x4 ordered dithering. */
fun toGColor8(rgb: IntArray, w: Int, h: Int): ByteArray {
    val out = ByteArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val offset = (BAYER[(y and 3) * 4 + (x and 3)] / 16.0 - 0.5) * 85
            val i = y * w + x
            var c = 0xC0
            for (ch in 0 until 3) {
                val value = (rgb[i] shr (16 - ch * 8)) and 0xFF
                val q = ((value + offset) / 85).roundToInt().coerceIn(0, 3)
                c = c or (q shl (4 - ch * 2))
            }
            out[i] = c.toByte()
        }
    }
    return out
}
