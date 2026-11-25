package org.luckyobserver.getafterit.pebble

import android.content.Context
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped wrapper around [DefaultPebbleSender]. Owns the sender's
 * lifecycle (lazy init, explicit close on run stop) so callers can fire-and-
 * forget metric updates without per-message setup overhead.
 *
 * [DefaultPebbleSender] internally manages a bound-service connection;
 * constructing/closing it per message (one or more a second during a run)
 * would churn the binder. The singleton keeps one connection.
 *
 * Threading: all sender methods are suspend; call from a coroutine. Callers
 * launch on their own scope (e.g. `Activity.lifecycleScope`).
 */
object PebbleMessenger {

    private const val TAG = "PebbleMessenger"

    /** Watchapp UUID. Must match `watchapp/package.json`. */
    val WATCHAPP_UUID: UUID = UUID.fromString("622e9f96-77f2-4786-aa7d-389cf4852561")

    @Volatile private var sender: PebbleSender? = null

    /**
     * Consecutive failure counter for the cached [sender]. Incremented when
     * `sendDataToPebble` / `startAppOnTheWatch` throw, return null, or return a
     * non-Success [TransmissionResult]; reset on any success or [close]. After
     * [MAX_CONSECUTIVE_FAILURES] in a row the sender is closed so the next
     * call rebuilds the bound-service connection (covers the
     * "Pebble Android app was killed / crashed" case). No retry/backoff —
     * retry is a screen-level concern.
     *
     * AtomicInteger because send/startWatchapp are launched on the listener
     * service's coroutineScope and can run concurrently.
     */
    private val failureCount = AtomicInteger(0)
    private const val MAX_CONSECUTIVE_FAILURES = 3

    private fun recordResult(success: Boolean) {
        if (success) {
            failureCount.set(0)
        } else if (failureCount.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES) {
            Log.w(TAG, "Resetting Pebble sender after $MAX_CONSECUTIVE_FAILURES consecutive failures")
            close()
        }
    }

    /**
     * Lazily create (or return) the cached [PebbleSender]. Uses the application
     * context to outlive any single Activity / Service callsite.
     */
    private fun getOrCreate(context: Context): PebbleSender {
        // Double-checked locking — sender is @Volatile.
        sender?.let { return it }
        synchronized(this) {
            sender?.let { return it }
            val newSender = DefaultPebbleSender(context.applicationContext)
            sender = newSender
            return newSender
        }
    }

    /** Idempotent close. Safe to call multiple times. */
    fun close() {
        synchronized(this) {
            sender?.close()
            sender = null
            failureCount.set(0)
        }
    }

    // === Send helpers ===

    /** Tell the watch a run is recording (transitions idle → active-run). */
    suspend fun sendRunStarted(context: Context) {
        send(context, mapOf(Keys.RUN_STARTED to PebbleDictionaryItem.UInt8(1)))
    }

    /**
     * Tell the watch a run has stopped. Acks watch-initiated CMD_STOP (the
     * stopping screen waits for this) and also drives the watch out of
     * active-run when the user stops the run from the companion's Home
     * screen instead of the watch.
     */
    suspend fun sendRunStopped(context: Context) {
        send(context, mapOf(Keys.RUN_STOPPED to PebbleDictionaryItem.UInt8(1)))
    }

    /**
     * Launch our watchapp on the connected Pebble (PebbleKit
     * `startAppOnTheWatch`). Used by the Start Run button so the user
     * doesn't have to open the watchapp manually. No-op if already open.
     */
    suspend fun startWatchapp(context: Context) {
        val s = getOrCreate(context)
        val result: Map<*, TransmissionResult>? = try {
            s.startAppOnTheWatch(WATCHAPP_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "startAppOnTheWatch failed", e)
            recordResult(success = false)
            return
        }
        if (result == null) {
            Log.d(TAG, "startAppOnTheWatch: Pebble app not reachable")
            recordResult(success = false)
            return
        }
        var allOk = true
        for ((watch, tr) in result) {
            if (tr !is TransmissionResult.Success) {
                Log.d(TAG, "startAppOnTheWatch to $watch: $tr")
                allOk = false
            }
        }
        recordResult(success = allOk)
    }

    /**
     * Push live metrics to the watch. Pace is omitted when null (treated as
     * "stopped" — watch renders "--:--").
     *
     * - paceSecPerKm: filtered GPS speed as pace, capped at 3600 by the caller.
     * - timeSec:      moving time.
     * - distM, gainM, lossM: whole meters.
     */
    suspend fun sendMetrics(
        context: Context,
        paceSecPerKm: Int?,
        timeSec: Long,
        distM: Long,
        gainM: Long,
        lossM: Long,
        strapHr: Int?,
    ) {
        val dict = buildMap<UInt, PebbleDictionaryItem> {
            paceSecPerKm?.let {
                put(Keys.PACE_CURRENT, PebbleDictionaryItem.UInt16(it.coerceIn(0, 3600)))
            }
            put(Keys.TIME, PebbleDictionaryItem.UInt32(timeSec))
            put(Keys.DISTANCE, PebbleDictionaryItem.UInt32(distM))
            put(Keys.GAIN, PebbleDictionaryItem.UInt32(gainM))
            put(Keys.LOSS, PebbleDictionaryItem.UInt32(lossM))
            strapHr?.let { put(Keys.HR, PebbleDictionaryItem.UInt16(it)) }
        }
        send(context, dict)
    }

    suspend fun sendError(context: Context, message: String) {
        send(context, mapOf(Keys.ERROR to PebbleDictionaryItem.Text(message)))
    }

    // One message in flight at a time: the watch's inbox holds a single message,
    // so overlapping map chunks and stats would be NACKed as busy.
    private val sendLock = Mutex()

    /** Sends [dict] to the watchapp; true when every watch acknowledged it. */
    suspend fun send(context: Context, dict: PebbleDictionary): Boolean = sendLock.withLock {
        val s = getOrCreate(context)
        val result: Map<*, TransmissionResult>? = try {
            s.sendDataToPebble(WATCHAPP_UUID, dict)
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            recordResult(success = false)
            return false
        }
        if (result == null) {
            // Pebble companion app not reachable (uninstalled, or not the
            // currently-selected app per PebbleAndroidAppPicker). Home
            // already reflects the connection state.
            Log.d(TAG, "Pebble app not reachable; dropping ${dict.keys}")
            recordResult(success = false)
            return false
        }
        // Non-success results aren't user-actionable mid-run — the watch
        // dims its own metrics on staleness. Log only.
        var allOk = true
        for ((watch, tr) in result) {
            if (tr !is TransmissionResult.Success) {
                Log.d(TAG, "send to $watch: $tr")
                allOk = false
            }
        }
        recordResult(success = allOk)
        allOk
    }
}
