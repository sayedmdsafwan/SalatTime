package com.safwan.prayertime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager alarms are wiped on reboot. This re-reads the last recipe
 * [NativeBridge] synced to SharedPreferences, recomputes today's and
 * tomorrow's times fresh via [Astro] (the device may have been off across
 * a date change), and reschedules the widget-tick alarms — no WebView/JS
 * needed at boot time. Also re-arms the daily recompute tick.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmScheduler.recomputeAndSchedule(context)
        }
    }
}
