package org.luckyobserver.getafterit.pebble

/**
 * AppMessage numeric keys. Numbers match `watchapp/src/c/app_message.h`.
 *
 * Per `BasePebbleListenerService.onMessageReceived`, **all received numbers
 * arrive as UInt32 or Int32 regardless of the wire size the watch used**, so
 * `data[KEY_CMD_STOP]` is a `PebbleDictionaryItem.UInt32` even though the
 * watch wrote it as uint8. When sending the other way we use the requested
 * wire size so the watch reads via `dict_find(iter, key)->value->uint16` etc.
 */
object Keys {
    // Wire keys are UInt on the Kotlin side (PebbleDictionary = Map<UInt, …>).

    // Watch → Companion
    const val CMD_START:    UInt = 1u
    const val CMD_STOP:     UInt = 2u
    const val ZOOM:         UInt = 3u    // int32, map zoom level
    const val SCROLL_X:     UInt = 4u    // int32, map dragged from the runner, screen pixels
    const val SCROLL_Y:     UInt = 5u    // int32
    const val MODE:         UInt = 6u    // activity picked on the watch (MODE_RUN etc.)
    const val HELLO:        UInt = 7u    // watchapp ready to receive; reply with syncWatch
    const val PAUSED:       UInt = 8u    // 1 while the run is paused; on every watch message during a run

    // Companion → Watch
    const val RUN_STARTED:  UInt = 110u
    const val RUN_STOPPED:  UInt = 111u
    const val PACE_CURRENT: UInt = 120u  // uint16, sec/km capped 3600
    const val TIME:         UInt = 122u  // uint32, moving time in seconds
    const val DISTANCE:     UInt = 123u  // uint32, meters
    const val GAIN:         UInt = 124u  // uint32, elevation gain in meters
    const val LOSS:         UInt = 125u  // uint32, elevation loss in meters

    // Companion → Watch, map (MapSession)
    const val FIX:          UInt = 126u  // uint8, a GPS fix arrived
    const val ELEV:         UInt = 127u  // int32, current elevation in meters
    const val REMAIN:       UInt = 128u  // int32, meters to the end of the course, -1 without one
    const val OFF_COURSE:   UInt = 129u  // uint8
    const val IMG_OFFSET:   UInt = 130u  // int32, byte offset into the 200x228 GColor8 image
    const val IMG_DATA:     UInt = 131u  // bytes
    const val MARKER_X:     UInt = 132u  // int32, position on the current image
    const val MARKER_Y:     UInt = 133u  // int32
    const val MARKER_DIR:   UInt = 134u  // int32, degrees clockwise from image up, -1 unknown
    const val ERROR:        UInt = 135u  // text
    const val HR:           UInt = 136u  // uint16, chest-strap heart rate, bpm

    // Companion → Watch, settings (int32 scalars, slot lists as bytes)
    const val SET_MILES:      UInt = 140u
    const val SET_BACKLIGHT:  UInt = 141u
    const val SET_MAX_HR:     UInt = 142u
    const val SET_OVERLAY:    UInt = 143u
    const val SET_MAP_SLOTS:  UInt = 144u  // per map activity: 3 map screens x 4 + a stats page of 6 Stat values
    const val SET_MODE:       UInt = 146u
    const val SET_STATS_SLOTS: UInt = 147u  // Run: 3 screens x 6 Stat values
    const val SET_MODES:      UInt = 150u  // bit per activity: the ones the watch cycles through
}
