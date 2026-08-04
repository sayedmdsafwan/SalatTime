package com.safwan.prayertime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import java.util.Calendar

/**
 * Shared SharedPreferences contract + AlarmManager scheduling for the
 * home-screen widget's silent per-waqt refresh tick, and the source of
 * the numbers [WidgetUpdater] reads to render the widget.
 *
 * ARCHITECTURE:
 * The WebView side (index.html) is still the only source of truth for
 * what's on the home screen, and still owns location lookup, the city
 * database, onboarding, etc. But prayer-time MATH itself is no longer
 * something only JS can do: [Astro] is a native Kotlin port of the exact
 * same formulas as index.html's `Astro` module. JS pushes a small
 * "recipe" — lat/lon, timezone, madhhab, calc method, per-prayer minute
 * offsets, language, time format — via [NativeBridge.syncPrayerData], and
 * native computes today's and tomorrow's times itself with that recipe,
 * exactly like JS does for the screen. That computed output (not a copy
 * of JS's numbers) is what gets written to prefs for the widget and what
 * the widget-tick alarms get scheduled against.
 *
 * This means native never has to guess. Previously, when the app hadn't
 * been opened, the widget's data simply went stale until the app was
 * reopened. That's gone. Instead:
 *
 *  - [recomputeAndSchedule] re-derives today's + tomorrow's times from
 *    the stored recipe via [Astro] every time it runs, and reschedules
 *    every prayer's silent widget-tick alarm against the correct
 *    occurrence (today's if still upcoming, tomorrow's otherwise — both
 *    freshly computed, never today's time +24h).
 *  - It runs on every JS sync (recipe changed), on every widget tick
 *    firing (a natural daily heartbeat), after boot, AND once daily at
 *    ~00:02 local time via [DailyRecomputeReceiver] — because a
 *    widget-only user who never reopens the app still needs the widget
 *    itself to stay accurate. That daily tick re-arms itself every time
 *    it fires, the same self-chaining pattern already used for the
 *    per-prayer widget ticks.
 */
object AlarmScheduler {

    const val PREFS_NAME = "salat_widget_prefs"
    val PRAYER_KEYS = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")

    // Matches index.html's DISPLAY_END_GAP: a purely cosmetic 1-minute trim
    // off a prayer's printed END when it's astronomically identical to the
    // next prayer's START, so the widget never shows the same minute twice
    // (e.g. Dhuhr ending 4:44 right above Asr starting 4:44). Never applied
    // to the START time used for the widget's active-state — only to
    // what's displayed as an end boundary. Applied to Fajr's end (sunrise)
    // too, same as the JS side — shown a minute before sunrise as a safety
    // margin rather than only to avoid visually colliding with a card.
    private const val DISPLAY_END_GAP = 1.0 / 60.0

    // Mirrors index.html's START_SAFETY_BUFFER: a flat, non-user-adjustable
    // 1 minute added to every prayer's raw START before scheduling its
    // widget tick or writing the widget's start time. hourFloatToMillis()
    // below rounds a fractional-hour instant to the nearest whole minute —
    // a true start at e.g. 12:06:20 can round DOWN to 12:06:00, ticking the
    // widget ~20s before the prayer has actually begun. This buffer
    // guarantees the tick never fires early, regardless of where in the
    // minute the true instant falls. Applied in offsetOf() below, stacked
    // underneath the user's own optional per-prayer offset from
    // Settings > Add Buffer Time.
    private const val START_SAFETY_BUFFER = 1.0 / 60.0

    private const val LAT_UNSET = -999f

    private const val DAILY_RECOMPUTE_REQUEST_CODE = 4999

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun dailyRecomputePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyRecomputeReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, DAILY_RECOMPUTE_REQUEST_CODE, intent, flags)
    }

    /** Places [hourFloat] (hours-since-midnight; NOT assumed to be in
     *  [0,24) — see below) onto the wall-clock date of [dayCal], returning
     *  the resulting instant. This never guesses which BASE day to use —
     *  the caller already picked today vs. tomorrow based on a real
     *  astronomical computation for that specific date — but [hourFloat]
     *  itself can legitimately fall outside [0,24): offsetOf() adds
     *  START_SAFETY_BUFFER plus the user's own 0-3min buffer on top of the
     *  raw astronomical start time without re-wrapping it, so a prayer
     *  (realistically only Isha) whose natural time is already within a
     *  few minutes of midnight can end up with an hourFloat of e.g. 24.03
     *  — meaning "00:03 the NEXT day", not "00:03 today". Using
     *  floor-division/modulo on total minutes (rather than
     *  Astro.fixHour()+Calendar.set(), which wrapped the clock time
     *  correctly but silently left the calendar DAY unchanged) rolls
     *  [dayCal] forward by however many whole days [hourFloat] actually
     *  represents, so the resulting instant lands 24h later than it used
     *  to for exactly this case — previously the widget's silent tick for
     *  such an Isha would fire a full day early. */
    private fun hourFloatToMillis(hourFloat: Double, dayCal: Calendar): Long {
        val cal = dayCal.clone() as Calendar
        val totalMinutes = Math.round(hourFloat * 60.0)
        val dayShift = Math.floorDiv(totalMinutes, 1440L)
        val minuteOfDay = Math.floorMod(totalMinutes, 1440L)
        val h = (minuteOfDay / 60).toInt()
        val m = (minuteOfDay % 60).toInt()
        if (dayShift != 0L) cal.add(Calendar.DAY_OF_YEAR, dayShift.toInt())
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** A Calendar set to [atMillis], but in a fixed-offset zone of
     *  [hoursOffset] hours from UTC — NOT the device's own default
     *  timezone. Its .get(YEAR/MONTH/DAY_OF_MONTH) then correctly reflects
     *  the TARGET location's wall-clock calendar day, and passing it to
     *  hourFloatToMillis() correctly converts an hour-of-day float using
     *  the target's offset rather than the device's, when the two differ
     *  (manual city/coordinates in another timezone — see the doc comment
     *  on recomputeAndSchedule for the bug this fixes). */
    private fun calendarAtOffset(hoursOffset: Double, atMillis: Long): Calendar {
        val tz = java.util.SimpleTimeZone((hoursOffset * 3600000).toInt(), "prayer_target_offset")
        return Calendar.getInstance(tz).apply { timeInMillis = atMillis }
    }

    private fun widgetTickRequestCode(key: String): Int = 4300 + PRAYER_KEYS.indexOf(key)

    private fun widgetTickPendingIntent(context: Context, key: String): PendingIntent {
        val intent = Intent(context, WidgetTickReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, widgetTickRequestCode(key), intent, flags)
    }

    /** Arms a silent (no notification) tick at [key]'s start time, purely
     *  so the widget's "RUNNING NOW" row flips the instant a waqt begins,
     *  with no user interaction required. Previously the widget only
     *  re-rendered on an app sync, app resume, or the once-daily 00:02
     *  tick — so the bold "running now" row could stay on the old prayer
     *  until the user manually tapped refresh or reopened the app. This
     *  alarm exists purely to close that gap; it never posts anything
     *  visible on its own.
     *  Uses the inexact `setAndAllowWhileIdle` rather than an exact alarm
     *  — a widget-only user is never asked for any special permission for
     *  this, and a few seconds of slack here is invisible on a widget
     *  that already only shows minute precision. */
    private fun scheduleWidgetTickAt(context: Context, key: String, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = widgetTickPendingIntent(context, key)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun cancelWidgetTick(context: Context, key: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(widgetTickPendingIntent(context, key))
    }

    /** Once-a-day tick that keeps the widget accurate even for users who
     *  never open the app — see file doc. Deliberately uses the inexact
     *  `setAndAllowWhileIdle` rather than an exact alarm: a background
     *  data refresh doesn't need to-the-second precision, and doesn't
     *  require any special alarm permission. */
    fun scheduleDailyRecompute(context: Context) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 2)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = dailyRecomputePendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    // ---------- Recipe (everything Astro needs to recompute any day) ----------

    private fun writeRecipe(context: Context, recipe: PrayerRecipe) {
        val editor = prefs(context).edit()
        editor.putFloat("recipe_lat", recipe.lat.toFloat())
        editor.putFloat("recipe_lon", recipe.lon.toFloat())
        editor.putString("recipe_tz", recipe.tzId ?: "")
        editor.putString("recipe_location_mode", recipe.locationMode ?: "")
        editor.putString("recipe_madhhab", recipe.madhhab)
        editor.putString("recipe_calc_method", recipe.calcMethod)
        for (key in PRAYER_KEYS) {
            editor.putFloat("recipe_offset_$key", (recipe.offsets[key] ?: 0.0).toFloat())
        }
        editor.putString("lang", recipe.lang)
        editor.putString("time_format", recipe.timeFormat)
        editor.putString("recipe_nearest_tz", recipe.nearestTz ?: "")
        // -1f is a safe "unset" sentinel: a real distance is never negative.
        editor.putFloat("recipe_nearest_dist_km", recipe.nearestDistanceKm?.toFloat() ?: -1f)
        editor.apply()
    }

    private fun readRecipe(context: Context): PrayerRecipe? {
        val p = prefs(context)
        val lat = p.getFloat("recipe_lat", LAT_UNSET)
        if (lat == LAT_UNSET) return null
        val lon = p.getFloat("recipe_lon", 0f)
        val tzId = p.getString("recipe_tz", "")?.ifEmpty { null }
        val locationMode = p.getString("recipe_location_mode", "")?.ifEmpty { null }
        val madhhab = p.getString("recipe_madhhab", "hanafi") ?: "hanafi"
        val calcMethod = (p.getString("recipe_calc_method", "karachi") ?: "karachi")
            // Mirrors index.html's same migration: a recipe synced by a
            // pre-update app build may still say "makkah" here. Without this,
            // Astro.METHODS[methodKey] would miss and silently fall back to
            // Karachi the next time this fires before the app is reopened.
            .let { if (it == "makkah") "makkah90" else it }
        val offsets = PRAYER_KEYS.associateWith { p.getFloat("recipe_offset_$it", 0f).toDouble() }
        val lang = p.getString("lang", "en") ?: "en"
        val timeFormat = p.getString("time_format", "12") ?: "12"
        val nearestTz = p.getString("recipe_nearest_tz", "")?.ifEmpty { null }
        val nearestDist = p.getFloat("recipe_nearest_dist_km", -1f).let { if (it < 0f) null else it.toDouble() }
        return PrayerRecipe(
            lat.toDouble(), lon.toDouble(), tzId, locationMode, madhhab, calcMethod,
            offsets, lang, timeFormat, nearestTz, nearestDist
        )
    }

    /** Called from [NativeBridge] with a freshly-synced recipe from the
     *  WebView — persists it, then immediately recomputes+reschedules
     *  from it (see [recomputeAndSchedule]) and arms the daily tick. */
    fun syncRecipeAndSchedule(context: Context, recipe: PrayerRecipe) {
        writeRecipe(context, recipe)
        recomputeAndSchedule(context)
    }

    /** Adds the flat, non-adjustable [START_SAFETY_BUFFER] plus the
     *  user's own per-prayer minute setting (Settings > Add Buffer Time)
     *  on top of Astro's raw astronomical start time — the two are
     *  independent and both additive: this one function is where they
     *  actually get stacked together into the single number everything
     *  downstream (widget ticks, displayed start time) uses. */
    private fun offsetOf(recipe: PrayerRecipe, key: String, value: Double?): Double? {
        if (value == null) return null
        val minutes = recipe.offsets[key] ?: 0.0
        return value + START_SAFETY_BUFFER + minutes / 60.0
    }

    /**
     * The heart of the rewrite: re-derives today's and tomorrow's prayer
     * times from the stored recipe via [Astro] — a REAL recalculation for
     * whichever two calendar dates are actually current when this runs,
     * never a cached/repeated clock time — then:
     *  1. writes the widget-facing prefs ([WidgetUpdater] is unchanged),
     *  2. reschedules every prayer's silent widget-tick alarm against the
     *     correct occurrence (today's if still upcoming, otherwise
     *     tomorrow's — both freshly computed, never today's time +24h),
     *     so the widget's active row flips the instant each waqt begins
     *     with no user interaction required, and
     *  3. re-arms the daily tick for tomorrow.
     * No-ops gracefully if no recipe has been synced yet (e.g. before the
     * user finishes onboarding) — same as JS's own `if (state.lat===null)
     * return;` guard in renderHome().
     */
    fun recomputeAndSchedule(context: Context, forceLocationRefreshRearm: Boolean = false) {
        val recipe = readRecipe(context) ?: return
        val now = System.currentTimeMillis()

        // Resolve the target location's current UTC offset using the real
        // "now" instant (needed for correct DST lookup on a named zone),
        // then build today's/tomorrow's calendars IN THAT OFFSET rather
        // than the device's own default timezone. Without this, a manually
        // selected city in a different timezone than the device would get
        // the wrong calendar day near that location's midnight, AND
        // hourFloatToMillis() below would convert each prayer's hour-of-day
        // using the device's offset instead of the target's — silently
        // ticking the widget at the wrong absolute moment whenever the two
        // timezones differ (see TzResolver's own doc comment for why JS's
        // renderHome() needs the equivalent fix on its side too).
        val tzNow = TzResolver.offsetHours(recipe, now)
        val todayCal = calendarAtOffset(tzNow, now)
        val tomorrowCal = (todayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }

        val tzToday = TzResolver.offsetHours(recipe, todayCal.timeInMillis)
        val tzTomorrow = TzResolver.offsetHours(recipe, tomorrowCal.timeInMillis)

        val todayRaw = Astro.computeDay(
            todayCal.get(Calendar.YEAR), todayCal.get(Calendar.MONTH) + 1, todayCal.get(Calendar.DAY_OF_MONTH),
            recipe.lat, recipe.lon, tzToday, recipe.madhhab, recipe.calcMethod
        )
        val tomorrowRaw = Astro.computeDay(
            tomorrowCal.get(Calendar.YEAR), tomorrowCal.get(Calendar.MONTH) + 1, tomorrowCal.get(Calendar.DAY_OF_MONTH),
            recipe.lat, recipe.lon, tzTomorrow, recipe.madhhab, recipe.calcMethod
        )

        // Offset-adjusted START times (what widget ticks fire on, what the
        // widget shows as each prayer's start) — mirrors JS's
        // applyPrayerOffsets().
        val todayStart = mapOf(
            "fajr" to offsetOf(recipe, "fajr", todayRaw.fajr),
            "dhuhr" to offsetOf(recipe, "dhuhr", todayRaw.dhuhr),
            "asr" to offsetOf(recipe, "asr", todayRaw.asr),
            "maghrib" to offsetOf(recipe, "maghrib", todayRaw.maghrib),
            "isha" to offsetOf(recipe, "isha", todayRaw.isha)
        )
        val tomorrowStart = mapOf(
            "fajr" to offsetOf(recipe, "fajr", tomorrowRaw.fajr),
            "dhuhr" to offsetOf(recipe, "dhuhr", tomorrowRaw.dhuhr),
            "asr" to offsetOf(recipe, "asr", tomorrowRaw.asr),
            "maghrib" to offsetOf(recipe, "maghrib", tomorrowRaw.maghrib),
            "isha" to offsetOf(recipe, "isha", tomorrowRaw.isha)
        )

        // RAW (unbuffered) end boundaries — mirrors syncPrayerDataToNative's
        // `ends` map exactly, DISPLAY_END_GAP included, so the widget keeps
        // showing precisely what it always has.
        val ends = mapOf(
            "fajr" to todayRaw.sunrise?.minus(DISPLAY_END_GAP),
            "dhuhr" to todayRaw.asr?.minus(DISPLAY_END_GAP),
            "asr" to todayRaw.maghrib?.minus(DISPLAY_END_GAP),
            "maghrib" to todayRaw.isha?.minus(DISPLAY_END_GAP),
            "isha" to tomorrowRaw.fajr?.plus(24.0)?.minus(DISPLAY_END_GAP)
        )

        val editor = prefs(context).edit()
        for (key in PRAYER_KEYS) {
            todayStart[key]?.let { editor.putFloat("time_${key}_start", it.toFloat()) }
            ends[key]?.let { editor.putFloat("time_${key}_end", it.toFloat()) }
        }
        tomorrowStart["fajr"]?.let { editor.putFloat("tomorrow_fajr", it.toFloat()) }
        // Target location's current UTC offset, so WidgetUpdater can work out
        // "what time is it right now at the target location" itself (see its
        // computeActiveKey doc) instead of reading the device's own default
        // timezone — the same device-vs-target distinction TzResolver/
        // calendarAtOffset already handle for scheduling above.
        editor.putFloat("current_tz_offset", tzToday.toFloat())
        editor.apply()

        // Widget tick: armed for every prayer's next start unconditionally,
        // so the widget's active row always flips on time.
        for (key in PRAYER_KEYS) {
            val startToday = todayStart[key]
            val startTomorrow = tomorrowStart[key]
            val triggerAt = when {
                startToday != null && hourFloatToMillis(startToday, todayCal) > now ->
                    hourFloatToMillis(startToday, todayCal)
                startTomorrow != null -> hourFloatToMillis(startTomorrow, tomorrowCal)
                startToday != null -> hourFloatToMillis(startToday, todayCal) // last resort: re-arm today's slot
                else -> null
            }

            if (triggerAt != null) scheduleWidgetTickAt(context, key, triggerAt)
            else cancelWidgetTick(context, key)
        }

        WidgetUpdater.refreshAll(context)
        scheduleDailyRecompute(context)
        // Settings > Auto Location Update — arms/cancels the background
        // GPS-refresh tick to match the current recipe's locationMode.
        // Piggybacking here means it self-heals on every JS sync, daily
        // tick, widget tick, boot, and timezone change, with no extra
        // wiring needed at each of those call sites. [forceLocationRefreshRearm]
        // is only ever true from BootReceiver — see rearmAfterBoot's doc.
        if (forceLocationRefreshRearm) {
            LocationRefreshScheduler.rearmAfterBoot(context, recipe.locationMode)
        } else {
            LocationRefreshScheduler.ensureScheduled(context, recipe.locationMode)
        }
    }
}
