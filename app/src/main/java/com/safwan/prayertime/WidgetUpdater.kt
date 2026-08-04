package com.safwan.prayertime

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * Renders the home-screen widget's RemoteViews from whatever
 * [AlarmScheduler.PREFS_NAME] currently holds. Those prefs are written by
 * [AlarmScheduler.recomputeAndSchedule] — a native recalculation via
 * [Astro], refreshed both right after every WebView sync and once a day
 * on its own (see [DailyRecomputeReceiver]) so the widget stays accurate
 * whether or not the app has been opened recently. This class itself
 * just renders whatever's currently in prefs; it never runs the math.
 * Called both from [PrayerWidgetProvider] and directly after every
 * recompute so the widget reflects changes without waiting for the OS's
 * own update cycle.
 */
object WidgetUpdater {

    private val NAME_EN = mapOf(
        "fajr" to "Fajr", "dhuhr" to "Dhuhr", "asr" to "Asr", "maghrib" to "Maghrib", "isha" to "Isha"
    )
    private val NAME_BN = mapOf(
        "fajr" to "ফজর", "dhuhr" to "যোহর", "asr" to "আসর", "maghrib" to "মাগরিব", "isha" to "এশা"
    )
    private val ROW_NAME_ID = mapOf(
        "fajr" to R.id.row_fajr_name, "dhuhr" to R.id.row_dhuhr_name, "asr" to R.id.row_asr_name,
        "maghrib" to R.id.row_maghrib_name, "isha" to R.id.row_isha_name
    )
    private val ROW_TIME_ID = mapOf(
        "fajr" to R.id.row_fajr_time, "dhuhr" to R.id.row_dhuhr_time, "asr" to R.id.row_asr_time,
        "maghrib" to R.id.row_maghrib_time, "isha" to R.id.row_isha_time
    )
    private val ROW_TIME_BOLD_ID = mapOf(
        "fajr" to R.id.row_fajr_time_bold, "dhuhr" to R.id.row_dhuhr_time_bold, "asr" to R.id.row_asr_time_bold,
        "maghrib" to R.id.row_maghrib_time_bold, "isha" to R.id.row_isha_time_bold
    )

    private fun fixHour(h: Double): Double {
        var v = h % 24.0
        if (v < 0) v += 24.0
        return v
    }

    /** Mirrors index.html's activeKey unroll exactly, using the same
     *  time_${key}_start/_end values already written to prefs by
     *  [AlarmScheduler.recomputeAndSchedule] — no separate computation, so
     *  this can never disagree with what the row itself displays. Returns
     *  null if any prayer's time isn't available yet (e.g. before the
     *  recipe has synced once), same as the app showing no "running now"
     *  highlight in that case.
     *
     *  BUG FIX: those start/end values are hours-of-day at the TARGET
     *  location (already resolved via TzResolver/calendarAtOffset when
     *  they were written), so "what time is it right now" must be read in
     *  that same target offset — not the device's own default timezone.
     *  This mirrors the exact fix index.html's localDateAt() already
     *  applies for renderHome() (manual city/coords in a different
     *  timezone than the device, or a device clock whose timezone is
     *  stale). Falls back to the device's own timezone only if no offset
     *  has been recorded yet (e.g. widget added before the first sync). */
    private fun computeActiveKey(p: android.content.SharedPreferences): String? {
        val order = AlarmScheduler.PRAYER_KEYS
        val starts = order.map { p.getFloat("time_${it}_start", Float.NaN).toDouble() }
        val ends = order.map { p.getFloat("time_${it}_end", Float.NaN).toDouble() }
        if (starts.any { it.isNaN() } || ends.any { it.isNaN() }) return null

        val unrolledStart = DoubleArray(order.size)
        for (i in order.indices) {
            var s = fixHour(starts[i])
            if (i > 0 && s < unrolledStart[i - 1]) s += 24.0
            unrolledStart[i] = s
        }
        val unrolledEnd = DoubleArray(order.size)
        for (i in order.indices) {
            var e = fixHour(ends[i])
            if (e < unrolledStart[i]) e += 24.0
            unrolledEnd[i] = e
        }

        val offsetHours = p.getFloat("current_tz_offset", Float.NaN)
        val now = if (!offsetHours.isNaN()) {
            val tz = java.util.SimpleTimeZone((offsetHours * 3600000).toInt(), "widget_target_offset")
            java.util.Calendar.getInstance(tz)
        } else {
            java.util.Calendar.getInstance()
        }
        var nowU = now.get(java.util.Calendar.HOUR_OF_DAY) +
            now.get(java.util.Calendar.MINUTE) / 60.0 +
            now.get(java.util.Calendar.SECOND) / 3600.0
        if (nowU < unrolledStart[0]) nowU += 24.0

        for (i in order.indices) {
            if (nowU >= unrolledStart[i] && nowU < unrolledEnd[i]) return order[i]
        }
        return null
    }

    /** Mirrors index.html's fmtTime(): hourFloat (0-24, values >=24 wrap
     *  to the next day's clock time) -> "4:00 AM" / "16:00" per format. */
    private fun formatHour(hourFloat: Float, format24: Boolean): String {
        val totalMinutes = hourFloat.toDouble().times(60).roundToInt()
        val hFull = ((totalMinutes / 60) % 24 + 24) % 24
        val m = ((totalMinutes % 60) + 60) % 60
        return if (format24) {
            String.format("%02d:%02d", hFull, m)
        } else {
            val period = if (hFull >= 12) "PM" else "AM"
            var hh = hFull % 12
            if (hh == 0) hh = 12
            String.format("%d:%02d %s", hh, m, period)
        }
    }

    /** Builds the widget's full RemoteViews from current prefs: fills each
     *  prayer row's name/time text, toggles which row shows as the bold
     *  "running now" style (via [computeActiveKey]), and wires the two
     *  tap targets (refresh icon, and the title area to open the app). */
    fun buildRemoteViews(context: Context): RemoteViews {
        val p = AlarmScheduler.prefs(context)
        val lang = p.getString("lang", "en") ?: "en"
        val format24 = p.getString("time_format", "12") == "24"
        val names = if (lang == "bn") NAME_BN else NAME_EN

        val views = RemoteViews(context.packageName, R.layout.widget_prayer_times)
        val activeKey = computeActiveKey(p)
        for (key in AlarmScheduler.PRAYER_KEYS) {
            val start = p.getFloat("time_${key}_start", -1f)
            val end = p.getFloat("time_${key}_end", -1f)
            val nameId = ROW_NAME_ID[key] ?: continue
            val timeId = ROW_TIME_ID[key] ?: continue
            val timeBoldId = ROW_TIME_BOLD_ID[key] ?: continue
            views.setTextViewText(nameId, names[key])
            val timeText = if (start < 0f || end < 0f) "--:-- - --:--"
                else formatHour(start, format24) + " - " + formatHour(end, format24)
            // Only one of the plain/bold twin is ever visible — the other
            // still gets the same text set so it's ready to show instantly
            // if this row becomes/stops being the active one on a later
            // refresh, with no stale text flashing in between.
            views.setTextViewText(timeId, timeText)
            views.setTextViewText(timeBoldId, timeText)
            val isActive = key == activeKey
            views.setViewVisibility(timeId, if (isActive) android.view.View.GONE else android.view.View.VISIBLE)
            views.setViewVisibility(timeBoldId, if (isActive) android.view.View.VISIBLE else android.view.View.GONE)
        }

        val refreshIntent = android.content.Intent(context, PrayerWidgetProvider::class.java).apply {
            action = PrayerWidgetProvider.ACTION_REFRESH
        }
        val refreshPending = android.app.PendingIntent.getBroadcast(
            context, 0, refreshIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPending)

        // Tapping the widget body opens the app.
        val openIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (openIntent != null) {
            val openPending = android.app.PendingIntent.getActivity(
                context, 1, openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title, openPending)
        }

        return views
    }

    /** Plain re-render, no animation — used by every automatic refresh path
     *  (JS sync every ~30s while the app is open, app resume, boot). Only
     *  the explicit user tap on the widget's refresh button should animate;
     *  spinning on every silent background sync would be distracting and
     *  wasteful. */
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PrayerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = buildRemoteViews(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }

    /** Same re-render as [refreshAll], but with a quick spin on the refresh
     *  icon first — used only when the user actually taps the widget's
     *  refresh button ([PrayerWidgetProvider.ACTION_REFRESH]), so tapping
     *  it has visible "syncing" feedback. */
    fun refreshAllAnimated(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PrayerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        animateRefreshIcon(context, manager, ids)
    }

    /**
     * Gives tap feedback on the refresh button: spins the icon a full
     * turn over ~350ms so the user can see the widget is syncing, then
     * settles back on the freshly-rendered data. RemoteViews can't run
     * real animations, so this is faked with a handful of re-renders
     * posted a few ms apart, each with the icon rotated a bit further
     * via setFloat(..., "setRotation", angle) — a real View method
     * RemoteViews can call by reflection.
     * 11 frames over ~350ms ≈ 31fps, above the ~24fps the eye reads as
     * smooth motion (350ms * 24fps ≈ 8.4, so 9 frames is the floor).
     */
    private fun animateRefreshIcon(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val frameAngles = floatArrayOf(0f, 36f, 72f, 108f, 144f, 180f, 216f, 252f, 288f, 324f, 360f)
        val frameDelayMs = 35L
        for ((index, angle) in frameAngles.withIndex()) {
            handler.postDelayed({
                val views = buildRemoteViews(context)
                // 360 == 0 visually; land back on 0 so it doesn't creep on repeated taps.
                views.setFloat(R.id.widget_refresh, "setRotation", if (angle == 360f) 0f else angle)
                for (id in ids) manager.updateAppWidget(id, views)
            }, frameDelayMs * index)
        }
    }
}
