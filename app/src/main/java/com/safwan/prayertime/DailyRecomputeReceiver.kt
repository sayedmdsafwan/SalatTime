package com.safwan.prayertime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires once a day (armed by [AlarmScheduler.scheduleDailyRecompute],
 * re-armed every time it fires) to keep the widget astronomically
 * accurate even when the app itself hasn't been opened in days. This is
 * what makes the widget-only usage pattern (open the app once, then only
 * ever glance at the widget) fully self-sufficient:
 * [AlarmScheduler.recomputeAndSchedule] re-derives today's/tomorrow's
 * times from the last-synced recipe via [Astro] on every fire, it
 * doesn't just replay old numbers.
 */
class DailyRecomputeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.recomputeAndSchedule(context)
    }
}
