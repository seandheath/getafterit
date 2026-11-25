package org.luckyobserver.getafterit.hr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateMeasurementTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun eightBitValueWithContact() {
        // Polar H10 style: contact reported and detected, RR intervals present.
        assertEquals(142, HeartRateMeasurement.parse(bytes(0x16, 142, 0x10, 0x03)))
    }

    @Test fun sixteenBitValue() {
        assertEquals(300, HeartRateMeasurement.parse(bytes(0x01, 0x2C, 0x01)))
    }

    @Test fun noContactGivesNull() {
        assertNull(HeartRateMeasurement.parse(bytes(0x04, 90)))
    }

    @Test fun zeroWhileSettlingGivesNull() {
        // The H10's first readings after connecting.
        assertNull(HeartRateMeasurement.parse(bytes(0x16, 0)))
    }

    @Test fun contactNotReportedStillTrusted() {
        assertEquals(75, HeartRateMeasurement.parse(bytes(0x00, 75)))
    }
}
