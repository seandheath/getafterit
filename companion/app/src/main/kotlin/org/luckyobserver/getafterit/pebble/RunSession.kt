package org.luckyobserver.getafterit.pebble

import android.content.Context

/** Whether a run is being recorded, persisted so a restarted companion carries on with it. */
object RunSession {
    private const val PREFS = "getafterit"
    private const val KEY_STARTED = "run_started_at"
    private const val KEY_PAUSED = "run_paused"

    /**
     * True between PebbleListenerService.onAppOpened and onAppClosed for our
     * watchapp UUID. Reflects watchapp lifecycle on the wrist, NOT just
     * Bluetooth connection state.
     */
    @Volatile var watchAppOpen: Boolean = false

    /** When the run in progress started (wall clock ms), or null when there is none. */
    fun startedAt(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_STARTED, 0L).takeIf { it != 0L }

    fun active(context: Context) = startedAt(context) != null

    /** Paused from the watch; nothing is recorded meanwhile. */
    fun paused(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PAUSED, paused).commit()
    }

    // commit, not apply: a crash right after start must not lose the run.
    fun start(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_STARTED, System.currentTimeMillis()).remove(KEY_PAUSED).commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_STARTED).remove(KEY_PAUSED).commit()
    }
}
