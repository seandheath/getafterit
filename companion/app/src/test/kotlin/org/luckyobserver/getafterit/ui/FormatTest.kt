package org.luckyobserver.getafterit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun durationSwitchesToHoursAtOneHour() {
        assertEquals("59:59", formatDuration(3599))
        assertEquals("1:00:00", formatDuration(3600))
    }

    @Test fun distanceInMilesAndKilometres() {
        assertEquals("1.00 mi", formatDistance(1609.344, miles = true))
        assertEquals("2.50 km", formatDistance(2500.0, miles = false))
    }

    @Test fun paceConvertsToMilesAndHidesStoppedOrTooSlow() {
        assertEquals("8:02 /mi", formatPace(300, miles = true))
        assertEquals("5:00 /km", formatPace(300, miles = false))
        assertEquals("--", formatPace(null, miles = true))
        assertEquals("--", formatPace(3600, miles = false))
    }

    @Test fun elevationInFeetOrMeters() {
        assertEquals("328 ft", formatElevation(100, miles = true))
        assertEquals("100 m", formatElevation(100, miles = false))
    }
}
