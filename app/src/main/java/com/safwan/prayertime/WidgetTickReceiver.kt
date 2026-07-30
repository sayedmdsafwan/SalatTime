package com.safwan.prayertime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires at every prayer's start time so the widget's "RUNNING NOW" row
 * flips the instant a waqt changes, with no user interaction required
 * (see [AlarmScheduler.recomputeAndSchedule]'s per-key loop). Posts
 * nothing visible — just recomputes and redraws.
 */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.recomputeAndSchedule(context)
    }
}
