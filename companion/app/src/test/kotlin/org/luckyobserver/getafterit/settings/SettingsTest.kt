package org.luckyobserver.getafterit.settings

import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.luckyobserver.getafterit.pebble.Keys

class SettingsTest {

    @Test fun defaultsAreValid() {
        assertNull(Settings().problem())
    }

    @Test fun rejectsMaxHrOutOfRange() {
        assertNotNull(Settings(maxHr = 99).problem())
        assertNotNull(Settings(maxHr = 251).problem())
    }

    @Test fun rejectsCustomTileUrlWithoutPlaceholdersOrHttps() {
        assertNotNull(Settings(tileSource = TILES_CUSTOM, customTileUrl = "https://example.com/{z}/{x}.png").problem())
        assertNotNull(Settings(tileSource = TILES_CUSTOM, customTileUrl = "http://example.com/{z}/{x}/{y}.png").problem())
    }

    @Test fun customUrlOnlyUsedWhenChosen() {
        val custom = "https://example.com/{z}/{x}/{y}.png"
        assertEquals(custom, Settings(tileSource = TILES_CUSTOM, customTileUrl = custom).tileUrl)
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", Settings(customTileUrl = custom).tileUrl)
    }

    @Test fun watchMessageCarriesEverySetting() {
        val msg = Settings(miles = false, maxHr = 180, mode = MODE_WORKOUT).toWatch()
        assertEquals(PebbleDictionaryItem.Int32(0), msg[Keys.SET_MILES])
        assertEquals(PebbleDictionaryItem.Int32(180), msg[Keys.SET_MAX_HR])
        val maps = (msg[Keys.SET_MAP_SLOTS] as PebbleDictionaryItem.Bytes).value
        assertEquals(MAP_MODES.size * (MAP_SCREENS * MAP_SLOTS + STAT_SLOTS), maps.size)
        assertArrayEquals(
            byteArrayOf(1, 2, 4, 6, 18, 10, 11, 13, 14, 18, 0, 0, 0, 0, 0, 1, 2, 4, 5, 6, 7),
            maps.copyOfRange(0, 21),
        )
        assertEquals(PebbleDictionaryItem.Int32(MODE_WORKOUT), msg[Keys.SET_MODE])
        assertEquals(STATS_SCREENS * STAT_SLOTS, (msg[Keys.SET_STATS_SLOTS] as PebbleDictionaryItem.Bytes).value.size)
        assertEquals(PebbleDictionaryItem.Int32(0b111111), msg[Keys.SET_MODES])
        assertEquals(8, msg.size)
    }

    @Test fun activitiesKnowTheirMapCourseAndType() {
        assertEquals(listOf(false, true, true, true, true, false), MODE_NAMES.indices.map(::showsMap))
        assertEquals(listOf(MODE_RUN_TRACK, MODE_HIKE_TRACK), MODE_NAMES.indices.filter(::followsCourse))
        assertEquals("hiking", activityId(MODE_HIKE_RECORD))
        assertEquals("running", activityId(MODE_RUN))
    }

    @Test fun rejectsNoModes() {
        assertNotNull(Settings(modes = 0).problem())
        assertEquals(false, Settings(modes = 0b0101).modeEnabled(MODE_RUN_TRACK))
    }
}
