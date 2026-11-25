package org.luckyobserver.getafterit.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MapMathTest {

    @Test fun worldPixelPutsOriginAtCentreOfWorld() {
        assertEquals(World(128.0, 128.0), worldPixel(0.0, 0.0, 0))
        val p = worldPixel(45.0, -122.0, 16)
        // z16 tile x = 58/360 * 2^16 = 10558.6; y = (1 - ln(1 + sqrt 2) / pi) / 2 * 2^16 = 23574.9.
        assertEquals(10558, Math.floorDiv(p.x.toInt(), TILE))
        assertEquals(23574, Math.floorDiv(p.y.toInt(), TILE))
    }

    @Test fun forwardsUpPutsHeadingAtTop() {
        // Heading east: a point 10 px east of the runner is 10 px above centre.
        val s = worldToScreen(10.0, 0.0, 90.0)
        assertEquals(0.0, s.u, 1e-9)
        assertEquals(-10.0, s.v, 1e-9)
    }

    @Test fun viewTilesCoversViewStraddlingTileEdges() {
        assertEquals(
            listOf(TileXY(1, 1), TileXY(2, 1), TileXY(1, 2), TileXY(2, 2)),
            viewTiles(World(512.0, 512.0), 0.0, 200, 228),
        )
        assertEquals(1, viewTiles(World(640.0, 640.0), 0.0, 200, 228).size)
    }

    @Test fun composeViewSamplesTilesGreysMissingOnesAndDrawsLines() {
        val red = IntArray(TILE * TILE) { 0xFFFF0000.toInt() }
        val rgb = composeView(
            World(256.0, 128.0), 0.0, 4, 2, mapOf(TileXY(0, 0) to red),
            listOf(Line(listOf(World(257.0, 127.0), World(257.0, 128.0)), 0x0000FF, 1)),
        )
        assertEquals(0xFF0000, rgb[0])        // tile 0/0
        assertEquals(0xC8C8C8, rgb[1 * 4 + 2]) // tile 1/0 missing
        assertEquals(0x0000FF, rgb[3])        // line
    }

    @Test fun toGColor8KeepsPureColoursExact() {
        val rgb = intArrayOf(0xFFFFFF, 0x000000, 0xFF00FF, 0x0055AA)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0xF3.toByte(), 0xC6.toByte()),
            toGColor8(rgb, 4, 1),
        )
    }
}
