package com.safwan.prayertime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires periodically (interval set by Settings > Auto Location Update,
 * once a day by default) to silently refresh the device's location and,
 * if it moved far enough to matter, recompute prayer times against it —
 * all without the user ever opening the app. See
 * [LocationRefreshScheduler] for the actual logic; this class only
 * wires it up to AlarmManager.
 *
 * Uses goAsync() because the fix itself arrives asynchronously via a
 * LocationListener callback — without it, the system would be free to
 * kill this process the instant onReceive() returns, before that
 * callback ever has a chance to fire.
 */
class LocationRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        LocationRefreshScheduler.refreshNowAndReschedule(context.applicationContext) {
            pendingResult.finish()
        }
    }
}
