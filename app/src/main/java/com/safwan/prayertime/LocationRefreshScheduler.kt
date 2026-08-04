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
    // What ensureScheduled last actually armed AlarmManager with — the
    // absolute fire time and the times/day it was computed from. Needed
    // because AlarmManager exposes no way to ask "is an alarm currently
    // pending for this PendingIntent" (a FLAG_NO_CREATE lookup only tells
    // you whether the PendingIntent object has ever existed, not whether
    // its alarm is still scheduled) — so this file has to track it itself.
    private const val KEY_NEXT_FIRE_AT = "location_refresh_next_fire_at"
    private const val KEY_ARMED_TIMES_PER_DAY = "location_refresh_armed_times_per_day"
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

    // How long a single tick waits for a fix before giving up. Generous
    // because a cold GPS fix with no network assistance (no cell/WiFi
    // signal to help it) can genuinely take 20-30s — and the next tick is
    // always safely re-armed before this fetch even starts, so a slow or
    // failed fix here only costs one skipped refresh, never breaks the chain.
    private const val FIX_TIMEOUT_MILLIS = 25_000L

    private fun prefs(context: Context): SharedPreferences = AlarmScheduler.prefs(context)

    fun timesPerDay(context: Context): Int =
        prefs(context).getInt(KEY_TIMES_PER_DAY, DEFAULT_TIMES_PER_DAY)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, LocationRefreshReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    private fun armAlarm(context: Context, intervalMillis: Long, times: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + intervalMillis
        val pi = pendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        prefs(context).edit()
            .putLong(KEY_NEXT_FIRE_AT, triggerAt)
            .putInt(KEY_ARMED_TIMES_PER_DAY, times)
            .apply()
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
        prefs(context).edit().remove(KEY_NEXT_FIRE_AT).remove(KEY_ARMED_TIMES_PER_DAY).apply()
    }

    /** Called every time [AlarmScheduler.recomputeAndSchedule] runs (JS
     *  sync, daily tick, widget tick — up to several times a day — and
     *  this feature's own tick) — arms the next tick at the currently
     *  configured frequency if [locationMode] is "gps", otherwise cancels
     *  any pending one.
     *
     *  Deliberately does NOT unconditionally re-arm on every call: an
     *  earlier version did, and because recomputeAndSchedule runs far
     *  more often than any of the configurable frequencies (every widget
     *  tick — up to 5x/day just from prayer-start ticks — is more
     *  frequent than even the 1x/day default's 24h interval), that pushed
     *  a still-pending alarm's fire time back to "now + interval" on
     *  every single call. The alarm would then never actually fire — a
     *  real trigger always arrived first and deferred it again. This only
     *  (re)arms when there's genuinely nothing valid already pending: no
     *  prior arm on record, that arm's time has already passed (which is
     *  exactly what's true right when this feature's own tick fires, so
     *  it still re-arms itself correctly), or the user changed the
     *  frequency since the last arm. */
    fun ensureScheduled(context: Context, locationMode: String?) {
        if (locationMode != "gps") {
            cancel(context)
            return
        }
        val times = timesPerDay(context).coerceIn(1, 24)
        val p = prefs(context)
        val nextFireAt = p.getLong(KEY_NEXT_FIRE_AT, -1L)
        val armedTimes = p.getInt(KEY_ARMED_TIMES_PER_DAY, -1)
        val stillValid = nextFireAt > System.currentTimeMillis() && armedTimes == times
        if (stillValid) return
        val intervalMillis = (24L * 60L * 60L * 1000L) / times
        armAlarm(context, intervalMillis, times)
    }

    /** Only for [BootReceiver]: AlarmManager wipes every alarm on reboot,
     *  but this file's own "when is it next due" bookkeeping survives in
     *  SharedPreferences — so [ensureScheduled] alone would see a still-
     *  future recorded time and wrongly conclude an alarm is still live.
     *  This forces a fresh arm regardless of that bookkeeping. */
    fun rearmAfterBoot(context: Context, locationMode: String?) {
        if (locationMode != "gps") {
            cancel(context)
            return
        }
        val times = timesPerDay(context).coerceIn(1, 24)
        val intervalMillis = (24L * 60L * 60L * 1000L) / times
        armAlarm(context, intervalMillis, times)
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
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true // pre-Android 10 never separated foreground/background location
        }
        return (fine || coarse) && background
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
        if (lm == null) {
            onDone()
            return
        }
        // Race every enabled provider rather than preferring NETWORK_PROVIDER:
        // network location needs cell/WiFi signal to resolve at all, which is
        // exactly what's weak or absent in the traveling-without-signal case
        // this feature exists for (e.g. mid-river on a launch). GPS_PROVIDER
        // works standalone off satellites, so it's the one that actually
        // matters there — but it can also be the slower of the two on a cold
        // start, so both are requested together and whichever answers first
        // wins; the loser is cancelled immediately.
        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { lm.isProviderEnabled(it) },
            LocationManager.NETWORK_PROVIDER.takeIf { lm.isProviderEnabled(it) }
        )
        if (providers.isEmpty()) {
            onDone()
            return
        }

        val mainHandler = Handler(Looper.getMainLooper())
        var finished = false
        val activeListeners = mutableListOf<LocationListener>()

        fun finishOnce() {
            if (finished) return
            finished = true
            for (l in activeListeners) {
                try { lm.removeUpdates(l) } catch (_: SecurityException) {}
            }
            onDone()
        }

        for (provider in providers) {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (finished) return
                    applyFixIfSignificant(context, p, location)
                    finishOnce()
                }
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            activeListeners.add(listener)
            try {
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
                // This provider refused; the others (if any) may still work,
                // so only bail out entirely once none are left registered.
            }
        }
        if (activeListeners.isEmpty()) {
            onDone()
            return
        }

        // requestSingleUpdate has no built-in timeout — bound how long a
        // tick waits for a fix before giving up. Generous on purpose: a
        // cold GPS fix with no network assistance can genuinely take this
        // long, and the next tick is already safely re-armed above
        // regardless of how this one ends.
        mainHandler.postDelayed({ finishOnce() }, FIX_TIMEOUT_MILLIS)
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

        // AUDIT FIX: previously this wrote only the new lat/lon, leaving
        // recipe_nearest_tz / recipe_nearest_dist_km (the nearest-known-
        // city timezone + distance, last set by the WebView's CityDB
        // lookup — see PrayerRecipe's doc) pointing at the OLD location.
        // TzResolver.offsetHours() uses that pair to decide whether to
        // override the device's own timezone (its "disagree by >0.4h and
        // within 300km" cross-check — see TzResolver's file doc). After a
        // significant background move those numbers describe a place the
        // user isn't at anymore: the distance could now read <=300km by
        // coincidence for a city that's actually far away (wrongly
        // triggering the override) or the reverse (wrongly skipping an
        // override that's now needed) — either way a silently wrong
        // offset for a user who, by definition, has "Automatic date &
        // time" off (that's the only case this cross-check ever changes
        // anything) and never reopened the app to let JS refresh these
        // fields with a fresh CityDB.nearest() lookup. Clearing them here
        // instead makes TzResolver fall back to the device's own
        // timezone until the app is next opened — the same safe default
        // this feature used before nearestTz/nearestDistanceKm cross-
        // checking existed, rather than trusting increasingly stale data.
        p.edit()
            .putFloat("recipe_lat", location.latitude.toFloat())
            .putFloat("recipe_lon", location.longitude.toFloat())
            .putString("recipe_nearest_tz", "")
            .putFloat("recipe_nearest_dist_km", -1f)
            .apply()
        AlarmScheduler.recomputeAndSchedule(context)
    }
}
