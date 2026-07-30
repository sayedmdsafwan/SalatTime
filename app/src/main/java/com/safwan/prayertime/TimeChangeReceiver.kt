package com.safwan.prayertime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Catches the three system broadcasts that can silently invalidate an
 * already-scheduled widget-tick alarm or an already-written widget time:
 *  - ACTION_TIMEZONE_CHANGED — user travels or changes the device timezone.
 *    [AlarmScheduler.recomputeAndSchedule] re-derives tz via [TzResolver]
 *    from the recipe's stored tzId, so a new offset needs a fresh compute.
 *  - ACTION_DATE_CHANGED — the calendar date is set/changed directly (not
 *    just the normal midnight rollover, which the self-rearming
 *    [DailyRecomputeReceiver] already covers).
 *  - ACTION_TIME_CHANGED — the wall clock itself is adjusted (manual set,
 *    NTP correction, etc.), which can leave AlarmManager's alarms
 *    targeting the wrong real-world instant until rescheduled.
 * In every case the fix is the same one [BootReceiver] and
 * [DailyRecomputeReceiver] already use: re-run
 * [AlarmScheduler.recomputeAndSchedule], which re-reads the last-synced
 * recipe, recomputes today's/tomorrow's times fresh via [Astro], rewrites
 * the widget prefs, and reschedules every widget-tick alarm against the
 * correct occurrence. No new scheduling logic here — this receiver only
 * decides *when* to trigger the existing one.
 *
 * All three actions are on Android's short list of "implicit broadcast"
 * exceptions that still reach a manifest-registered (not exported,
 * system-only-sendable) receiver even on API 26+, so no runtime
 * registration or foreground service is needed — this stays a plain,
 * battery-cheap manifest receiver like [BootReceiver].
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> AlarmScheduler.recomputeAndSchedule(context)
        }
    }
}
