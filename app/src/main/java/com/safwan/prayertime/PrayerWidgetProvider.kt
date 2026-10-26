package com.safwan.prayertime

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class PrayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Recompute BEFORE rendering. onUpdate is what the system calls for the
        // 24h updatePeriodMillis backstop (see prayer_widget_info.xml) and
        // whenever the widget is (re)added — repainting stored prefs alone would
        // just redraw whatever was already stale, which is how a widget could sit
        // on yesterday's times indefinitely once an alarm had been missed.
        AlarmScheduler.recomputeAndSchedule(context)
        // Still render explicitly: recomputeAndSchedule no-ops when no recipe has
        // been synced yet (app never opened since install), and the widget must
        // keep showing whatever it last had rather than nothing.
        val views = WidgetUpdater.buildRemoteViews(context)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            // The refresh tap must actually refresh: re-derive today's and
            // tomorrow's times from the stored recipe (which also re-arms the
            // per-prayer tick alarms), then play the redraw animation.
            AlarmScheduler.recomputeAndSchedule(context)
            WidgetUpdater.refreshAllAnimated(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.safwan.prayertime.WIDGET_REFRESH"
    }
}
