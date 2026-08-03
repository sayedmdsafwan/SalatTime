package com.safwan.prayertime

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Settings > Auto Location Update. Lets a GPS-mode user get the widget's
 * location refreshed silently in the background — the problem this
 * solves is that previously the widget/app only ever re-checked GPS when
 * the user manually opened the app and tapped "Refresh" (e.g. traveling
 * by launch through Barishal with the app never opened along the way).
 *
 * Deliberately a self-rearming AlarmManager alarm (same pattern as
 * [DailyRecomputeReceiver] / the widget-tick alarms in [AlarmScheduler])
 * rather than WorkManager, so this feature adds no new dependency to the
 * project. AlarmManager's inexact `setAndAllowWhileIdle` is exactly as
 * battery-friendly as what the app already uses elsewhere.
 *
 * Only ever active while the synced recipe's locationMode is "gps" —
 * manual city/coordinate users are never affected. A missing
 * ACCESS_BACKGROUND_LOCATION grant, a disabled location provider, or a
 * timed-out fix all make a tick a silent no-op rather than a crash; the
 * user can always fall back to the in-app "Refresh" button exactly like
 * before this feature existed.
 */
object LocationRefreshScheduler {

    private const val KEY_TIMES_PER_DAY = "location_refresh_times_per_day"
    private const val REQUEST_CODE = 4998
    private const val DEFAULT_TIMES_PER_DAY = 1

    // The only frequencies Settings > Auto Location Update exposes:
    // 1x/day is the always-on default (battery-light, fine for a user who
    // mostly stays put); 6/12/24x are opt-in for travel days.
    val ALLOWED_TIMES_PER_DAY = listOf(1, 6, 12, 24)

    // A fresh fix under this distance from the last one on record is
    // treated as GPS drift/noise, not real travel — the alarm still
    // fires and re-arms on schedule, but skips the write + recompute so
    // a stationary user doesn't get spurious widget churn.
    private const val MIN_SIGNIFICANT_MOVE_METERS = 1000f

    // How long a single tick waits for a fix before giving up.
    private const val FIX_TIMEOUT_MILLIS = 15_000L

    private fun prefs(context: Context): SharedPreferences = AlarmScheduler.prefs(context)

    fun timesPerDay(context: Context): Int =
        prefs(context).getInt(KEY_TIMES_PER_DAY, DEFAULT_TIMES_PER_DAY)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, LocationRefreshReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    private fun armAlarm(context: Context, intervalMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + intervalMillis
        val pi = pendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    /** Called every time [AlarmScheduler.recomputeAndSchedule] runs (JS
     *  sync, daily tick, widget tick, boot, timezone change, and this
     *  feature's own tick) — arms the next tick at the currently
     *  configured frequency if [locationMode] is "gps", otherwise
     *  cancels any pending one. Piggybacking on that single call site
     *  means this schedule self-heals everywhere recomputeAndSchedule
     *  already runs, with no extra wiring needed at each of those call
     *  sites. Always re-arms from "now" rather than trying to preserve a
     *  prior schedule — same simplicity as DailyRecomputeReceiver. */
    fun ensureScheduled(context: Context, locationMode: String?) {
        if (locationMode != "gps") {
            cancel(context)
            return
        }
        val times = timesPerDay(context).coerceIn(1, 24)
        val intervalMillis = (24L * 60L * 60L * 1000L) / times
        armAlarm(context, intervalMillis)
    }

    /** [NativeBridge.setLocationRefreshFrequency] — Settings > Auto
     *  Location Update. Clamps to the nearest allowed value rather than
     *  rejecting an unexpected input. */
    fun setFrequency(context: Context, timesPerDay: Int) {
        val clamped = ALLOWED_TIMES_PER_DAY.minByOrNull { Math.abs(it - timesPerDay) } ?: DEFAULT_TIMES_PER_DAY
        prefs(context).edit().putInt(KEY_TIMES_PER_DAY, clamped).apply()
        val locationMode = prefs(context).getString("recipe_location_mode", "")?.ifEmpty { null }
        ensureScheduled(context, locationMode)
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true // pre-Android 10 never separated foreground/background location
        }
        return fine && background
    }

    /** Does the actual work when [LocationRefreshReceiver] fires: fetches
     *  one fresh fix and, only if it moved far enough to matter, writes
     *  it into the same recipe prefs [AlarmScheduler] owns and triggers
     *  a recompute — exactly as if the user had just tapped the in-app
     *  "Refresh" button. [onDone] is always called exactly once, on
     *  every exit path, so the caller (a BroadcastReceiver using
     *  goAsync()) can reliably release its wake lock. */
    @Suppress("DEPRECATION")
    fun refreshNowAndReschedule(context: Context, onDone: () -> Unit) {
        val p = prefs(context)
        val locationMode = p.getString("recipe_location_mode", "")?.ifEmpty { null }
        if (locationMode != "gps") {
            cancel(context)
            onDone()
            return
        }

        // Re-arm the next tick up front: a fix that times out or throws
        // below must never silently kill future refreshes.
        ensureScheduled(context, locationMode)

        if (!hasBackgroundLocationPermission(context)) {
            onDone()
            return
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val provider = when {
            lm == null -> null
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (lm == null || provider == null) {
            onDone()
            return
        }

        val mainHandler = Handler(Looper.getMainLooper())
        var finished = false

        fun finishOnce(listener: LocationListener) {
            if (finished) return
            finished = true
            try { lm.removeUpdates(listener) } catch (_: SecurityException) {}
            onDone()
        }

        lateinit var listener: LocationListener
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (finished) return
                applyFixIfSignificant(context, p, location)
                finishOnce(listener)
            }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
            onDone()
            return
        }

        // requestSingleUpdate has no built-in timeout — bound how long a
        // tick waits for a fix before giving up.
        mainHandler.postDelayed({ finishOnce(listener) }, FIX_TIMEOUT_MILLIS)
    }

    private fun applyFixIfSignificant(context: Context, p: SharedPreferences, location: Location) {
        val prevLat = p.getFloat("recipe_lat", Float.NaN)
        val prevLon = p.getFloat("recipe_lon", Float.NaN)
        val moved = if (!prevLat.isNaN() && !prevLon.isNaN()) {
            val prev = Location("prev").apply {
                latitude = prevLat.toDouble()
                longitude = prevLon.toDouble()
            }
            prev.distanceTo(location) >= MIN_SIGNIFICANT_MOVE_METERS
        } else {
            true // no prior fix on record yet — always apply the first one
        }
        if (!moved) return

        p.edit()
            .putFloat("recipe_lat", location.latitude.toFloat())
            .putFloat("recipe_lon", location.longitude.toFloat())
            .apply()
        AlarmScheduler.recomputeAndSchedule(context)
    }
}
