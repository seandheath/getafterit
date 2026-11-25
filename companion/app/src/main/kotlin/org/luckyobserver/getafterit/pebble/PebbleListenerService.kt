package org.luckyobserver.getafterit.pebble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.luckyobserver.getafterit.MainActivity
import org.luckyobserver.getafterit.R
import org.luckyobserver.getafterit.cdm.CdmManager
import org.luckyobserver.getafterit.hr.HeartRateStrap
import org.luckyobserver.getafterit.hr.StrapStore
import org.luckyobserver.getafterit.map.MapSession
import org.luckyobserver.getafterit.metrics.RunLibrary
import org.luckyobserver.getafterit.metrics.RunRecorder
import org.luckyobserver.getafterit.metrics.TrackStats
import org.luckyobserver.getafterit.settings.activityId
import org.luckyobserver.getafterit.settings.MODE_WORKOUT
import org.luckyobserver.getafterit.settings.SettingsStore
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Receives AppMessages from the watch (via PebbleKitAndroid2's bound-service
 * mechanism) and records runs: GPS fixes from [MapSession], barometric
 * elevation and strap heart rate go to a [RunRecorder], and the totals go to
 * the watch every [STATS_PERIOD_MS].
 *
 * The Pebble app binds the service while the watchapp is open. During a run
 * it is also a started foreground service, so recording carries on with the
 * watchapp closed and the phone locked. [RunSession] persists that a run is in
 * progress; if Android kills the process, START_STICKY recreates the service
 * and [onCreate] resumes the recording from its file.
 *
 * Watch → Companion keys handled here (all idempotent):
 *   1  CMD_START → start a run, or resend RUN_STARTED for the one in progress
 *   2  CMD_STOP  → save the run as GPX and tell the watch it stopped
 *   8  PAUSED    → pause or resume recording
 *
 * Per the library docs, **received numbers always arrive as UInt32 or
 * Int32 regardless of the wire size the watch used**.
 */
class PebbleListenerService : BasePebbleListenerService() {

    // GPS and the watch's map.
    private val mapSession by lazy { MapSession(this, coroutineScope, ::onFix) }

    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val barometer by lazy { sensors.getDefaultSensor(Sensor.TYPE_PRESSURE) }
    // Barometric climb is good to a metre or two; GPS altitude wanders far more.
    private val recorder by lazy { RunRecorder(this, if (barometer != null) 3.0 else 10.0) }

    /** Smoothed barometric altitude (standard atmosphere), null until the first reading. */
    @Volatile private var baroAltM: Double? = null
    private val baroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0]).toDouble()
            baroAltM = baroAltM?.let { it + BARO_SMOOTHING * (alt - it) } ?: alt
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        if (RunSession.active(this)) {
            Log.d(TAG, "resuming the run in progress")
            recorder.resume()
            startRecording()
        }
        coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(STATS_PERIOD_MS)
                if (RunSession.active(this@PebbleListenerService)) pushMetrics()
            }
        }
    }

    override fun onDestroy() {
        strap.stop()
        mapSession.stop()
        sensors.unregisterListener(baroListener)
        // stopForeground is a no-op if we were never promoted — safe.
        stopForeground(STOP_FOREGROUND_REMOVE)
        PebbleMessenger.close()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SETTINGS_CHANGED -> {
                updateStrap()
                updateMap()
            }
            ACTION_START -> handleStart()
            ACTION_STOP -> coroutineScope.launch { handleStop() }
            // ACTION_FOREGROUND, or a restart after Android killed us mid-run (null intent).
            else -> {
                promoteToForeground()
                if (!RunSession.active(this)) dropForeground()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    /** Send the settings, then whether a run is in progress. */
    private suspend fun syncWatch() {
        PebbleMessenger.send(this, SettingsStore.get(this).toWatch())
        if (RunSession.active(this)) PebbleMessenger.sendRunStarted(this) else PebbleMessenger.sendRunStopped(this)
    }

    // === Foreground-service plumbing ===
    //
    // The service runs as a foreground service while a run is active so the
    // OS doesn't reap it mid-run: [startRecording] promotes it via
    // startForegroundService(ACTION_FOREGROUND), [handleStop] demotes it. The
    // notification is "ongoing" with LOW importance (silent).

    /** Lazy-create the recording channel. Safe to call on every promotion. */
    private fun ensureRecordingChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID_RECORDING) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID_RECORDING,
            getString(R.string.notification_channel_recording_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_recording_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildRecordingNotification(): Notification {
        // Tap → open MainActivity. FLAG_IMMUTABLE is required from API 31+.
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPi = PendingIntent.getActivity(
            this, /* requestCode= */ 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_RECORDING)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPi)
            .build()
    }

    private fun promoteToForeground() {
        ensureRecordingChannel()
        val notif = buildRecordingNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            // API 34+ requires the foregroundServiceType to be passed explicitly
            // when calling startForeground from within a Service whose manifest
            // declaration is foregroundServiceType="connectedDevice".
            startForeground(
                NOTIF_ID_RECORDING,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID_RECORDING, notif)
        }
        Log.d(TAG, "promoted to foreground")
    }

    private fun demoteFromForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "demoted from foreground")
    }

    // === Recording ===

    /** Foreground, GPS, barometer and strap on for the run in progress. */
    private fun startRecording() {
        startForegroundService(Intent(this, PebbleListenerService::class.java).setAction(ACTION_FOREGROUND))
        barometer?.let { sensors.registerListener(baroListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        updateMap()
        updateStrap()
    }

    private fun onFix(loc: Location, speedMps: Double?) {
        if (!RunSession.active(this) || RunSession.paused(this)) return
        recorder.add(
            loc.time, loc.latitude, loc.longitude, speedMps,
            if (loc.hasAltitude()) loc.altitude else null, baroAltM, currentStrapBpm(),
        )
    }

    // Live heart rate straight from the paired chest strap.
    private val strap by lazy { HeartRateStrap(this, ::onStrapBpm) }
    @Volatile private var strapBpm: Int? = null
    @Volatile private var strapAt = 0L

    private fun onStrapBpm(bpm: Int) {
        strapBpm = bpm
        strapAt = SystemClock.elapsedRealtime()
        // Each reading goes straight to the watch rather than waiting for the 5 s stats.
        coroutineScope.launch {
            PebbleMessenger.send(this@PebbleListenerService, mapOf(Keys.HR to PebbleDictionaryItem.UInt16(bpm)))
        }
    }

    private fun currentStrapBpm(): Int? =
        strapBpm.takeIf { SystemClock.elapsedRealtime() - strapAt <= STRAP_HR_FRESH_MS }

    /** While the watchapp is open, for the map, or a run is recording. */
    private fun needsSensors() = RunSession.watchAppOpen || RunSession.active(this)

    /** GPS and map when wanted, except in a workout, which needs neither. */
    private fun updateMap() {
        if (needsSensors() && SettingsStore.get(this).mode != MODE_WORKOUT) mapSession.start() else mapSession.stop()
    }

    /** Connect to the paired strap when it's wanted; otherwise disconnect. */
    private fun updateStrap() {
        strap.stop()
        val address = StrapStore.address(this)
        if (needsSensors() && SettingsStore.get(this).strapHr && address != null) strap.start(address)
    }

    /** Filtered GPS speed as pace, or null when stopped or the GPS has gone quiet. */
    private fun currentPaceSecPerKm(): Int? {
        val reading = LiveRun.speed
        if (reading.speedMps == null || SystemClock.elapsedRealtime() - reading.atMs > PACE_STALE_MS) return null
        return (1000 / reading.speedMps).roundToInt().coerceAtMost(TrackStats.MAX_PACE_SEC_PER_KM)
    }

    private fun pushMetrics() {
        val pace = currentPaceSecPerKm()
        val t = recorder.totals()
        val timeSec = t.movingMs / 1000
        val distM = t.distM.roundToLong()
        val gainM = t.gainM.roundToLong()
        val lossM = t.lossM.roundToLong()
        val strapHr = currentStrapBpm()
        LiveRun.stats.value = LiveRun.Stats(timeSec, distM, pace, gainM, lossM, strapHr, System.currentTimeMillis())
        coroutineScope.launch {
            PebbleMessenger.sendMetrics(
                context = this@PebbleListenerService,
                paceSecPerKm = pace,
                timeSec = timeSec,
                distM = distM,
                gainM = gainM,
                lossM = lossM,
                strapHr = strapHr,
            )
        }
    }

    // === Inbox / CMD handling ===

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != PebbleMessenger.WATCHAPP_UUID) {
            // Not our app. The library should normally route by UUID but
            // defense-in-depth doesn't hurt.
            Log.w(TAG, "Ignoring message for wrong UUID: $watchappUUID")
            return ReceiveResult.Nack
        }

        // A process that started while the watchapp was already open never got
        // onAppOpened; the watch's periodic nudge reaches us here instead.
        if (!RunSession.watchAppOpen) {
            Log.d(TAG, "message from an open watchapp we didn't know about — treating as opened")
            watchAppOpened()
        }
        data[Keys.ZOOM]?.let { mapSession.setView(it.asInt(), data[Keys.SCROLL_X]?.asInt() ?: 0, data[Keys.SCROLL_Y]?.asInt() ?: 0) }
        data[Keys.MODE]?.let {
            SettingsStore.save(this, SettingsStore.get(this).copy(mode = it.asInt()))
            updateMap()
        }
        data[Keys.PAUSED]?.let { setPaused(it.asInt() == 1) }
        if (data.containsKey(Keys.HELLO)) coroutineScope.launch(Dispatchers.IO) { syncWatch() }
        return when {
            data.containsKey(Keys.CMD_START) -> handleStart()
            data.containsKey(Keys.CMD_STOP)  -> handleStop()
            data.containsKey(Keys.ZOOM) || data.containsKey(Keys.MODE) -> ReceiveResult.Ack
            else -> {
                Log.d(TAG, "Unknown keys in inbox: ${data.keys}")
                ReceiveResult.Nack
            }
        }
    }

    /**
     * Start a run, from the watch or the phone. Going foreground from the
     * background (watch start, app closed) is only allowed with the watch's
     * CDM association, so without it the start is refused and the watch shows
     * its error.
     */
    private fun handleStart(): ReceiveResult {
        if (!RunSession.active(this)) {
            if (!CdmManager.isPaired(this)) {
                Log.w(TAG, "start without a CDM association — open the companion and pair the watch")
                return ReceiveResult.Nack
            }
            Log.d(TAG, "starting a run")
            RunSession.start(this)
            recorder.start()
            startRecording()
        }
        coroutineScope.launch { PebbleMessenger.sendRunStarted(this@PebbleListenerService) }
        return ReceiveResult.Ack
    }

    /** Pause or resume the run; the watch repeats its state, so only a change does anything. */
    private fun setPaused(paused: Boolean) {
        if (!RunSession.active(this) || paused == RunSession.paused(this)) return
        Log.d(TAG, if (paused) "run paused" else "run resumed")
        RunSession.setPaused(this, paused)
        if (paused) recorder.pause()
    }

    /** Save the run and leave the foreground. The watch's stopping screen waits for RUN_STOPPED. */
    private suspend fun handleStop(): ReceiveResult {
        if (RunSession.active(this)) {
            // Paused first so no fix lands while the file is saved; saving a long run takes a moment.
            RunSession.setPaused(this, true)
            val saved = withContext(Dispatchers.IO) {
                // The watch and phone only change the activity between recordings.
                recorder.finish(RunLibrary.dir(this@PebbleListenerService), activityId(SettingsStore.get(this@PebbleListenerService).mode))
            }
            Log.d(TAG, "run stopped, saved to ${saved?.name}")
            RunSession.clear(this)
            LiveRun.stats.value = null
            sensors.unregisterListener(baroListener)
            dropForeground()
            updateMap()
            updateStrap()
        }
        coroutineScope.launch { PebbleMessenger.sendRunStopped(this@PebbleListenerService) }
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == PebbleMessenger.WATCHAPP_UUID) {
            Log.d(TAG, "watchapp opened on $watch")
            watchAppOpened()
        }
    }

    private fun watchAppOpened() {
        RunSession.watchAppOpen = true
        coroutineScope.launch(Dispatchers.IO) { syncWatch() }
        updateMap()
        updateStrap()
    }

    /** Leave the foreground and started states. */
    private fun dropForeground() {
        demoteFromForeground()
        stopSelf()
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == PebbleMessenger.WATCHAPP_UUID) {
            RunSession.watchAppOpen = false
            Log.d(TAG, "watchapp closed on $watch")
            updateMap()
            updateStrap()
        }
    }

    companion object {
        private const val TAG = "PebbleListenerService"

        /** Sent to itself by [startRecording] to go foreground for the run. */
        private const val ACTION_FOREGROUND = "org.luckyobserver.getafterit.action.FOREGROUND"

        /** Re-check the mode, strap setting and pairing (sent after any of them changes in the app). */
        const val ACTION_SETTINGS_CHANGED = "org.luckyobserver.getafterit.action.SETTINGS_CHANGED"

        /** Start and stop the run from the phone app; same paths as the watch's CMD_START and CMD_STOP. */
        const val ACTION_START = "org.luckyobserver.getafterit.action.START"
        const val ACTION_STOP = "org.luckyobserver.getafterit.action.STOP"

        /** How often the totals go to the watch; matches the watchapp's HR sample period. */
        private const val STATS_PERIOD_MS = 5_000L

        // Foreground-service notification.
        private const val CHANNEL_ID_RECORDING = "org.luckyobserver.getafterit.recording"
        private const val NOTIF_ID_RECORDING = 1001

        private const val STRAP_HR_FRESH_MS = 15_000L
        private const val PACE_STALE_MS = 10_000L
        // Low-pass on pressure readings (a few a second): a time constant of a few seconds.
        private const val BARO_SMOOTHING = 0.05
    }
}

// Numbers from the watch arrive as Int32 or UInt32 whatever width it wrote them in.
private fun PebbleDictionaryItem.asInt(): Int = when (this) {
    is PebbleDictionaryItem.Int32 -> value
    is PebbleDictionaryItem.UInt32 -> value.toInt()
    else -> error("expected a number from the watch, got $this")
}
