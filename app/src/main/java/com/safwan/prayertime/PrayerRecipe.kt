package com.safwan.prayertime

/**
 * Everything [Astro] needs to independently recompute a day's prayer
 * times, as synced from the WebView side (index.html's `state`) via
 * [NativeBridge.syncPrayerData]. This is intentionally the recipe, not
 * the computed output — native derives today's/tomorrow's times from
 * this itself (see [AlarmScheduler.recomputeAndSchedule]) rather than
 * being handed a snapshot that goes stale the moment the app closes.
 */
/** A prayer's user-set buffer, in minutes: [start] nudges when its start
 *  time (and widget tick) is treated as beginning, [end] nudges when its
 *  window is treated as over. Independent, signed, applied on top of the
 *  raw Astro calc — see [AlarmScheduler.offsetOf] and the `ends` map in
 *  [AlarmScheduler.recomputeAndSchedule]. */
data class PrayerOffset(val start: Double = 0.0, val end: Double = 0.0)

data class PrayerRecipe(
    val lat: Double,
    val lon: Double,
    /** IANA timezone id (manual city mode), or null (manual coords / GPS) — see [TzResolver]. */
    val tzId: String?,
    /** "gps" | "manual" | null */
    val locationMode: String?,
    val madhhab: String,
    val calcMethod: String,
    /** per prayer key, applied after the raw Astro calc */
    val offsets: Map<String, PrayerOffset>,
    val lang: String,
    val timeFormat: String,
    /** IANA timezone id of the nearest known city to (lat, lon) — from
     *  index.html's CityDB.nearest(), same lookup renderHome() uses for its
     *  own deviceTz-vs-real-location cross-check. Null if unavailable. */
    val nearestTz: String? = null,
    /** Great-circle distance in km to [nearestTz]'s city. Null if unavailable. */
    val nearestDistanceKm: Double? = null
)
