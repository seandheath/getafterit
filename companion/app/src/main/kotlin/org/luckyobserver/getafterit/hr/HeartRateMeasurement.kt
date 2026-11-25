package org.luckyobserver.getafterit.hr

/**
 * Decodes the Bluetooth Heart Rate Measurement characteristic (0x2A37).
 * Byte 0 is flags: bit 0 = 16-bit value, bits 1-2 = sensor contact
 * (bit 2 says contact is reported, bit 1 says it's detected).
 */
object HeartRateMeasurement {
    /**
     * Beats per minute, or null when the strap reports no skin contact or a
     * reading of 0, which a Polar H10 sends while it settles after connecting.
     */
    fun parse(bytes: ByteArray): Int? {
        require(bytes.size >= 2) { "heart rate measurement too short: ${bytes.size} bytes" }
        val flags = bytes[0].toInt()
        val contactReported = flags and 0x04 != 0
        val contact = flags and 0x02 != 0
        if (contactReported && !contact) return null
        val bpm = if (flags and 0x01 != 0) {
            require(bytes.size >= 3) { "16-bit heart rate measurement too short" }
            (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        } else {
            bytes[1].toInt() and 0xFF
        }
        return bpm.takeIf { it > 0 }
    }
}
