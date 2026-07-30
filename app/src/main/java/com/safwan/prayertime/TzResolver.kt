package com.safwan.prayertime

import java.util.TimeZone

/**
 * Resolves a UTC offset (hours) for a [PrayerRecipe], mirroring index.html's
 * `TZ.offsetFor` + the three-way branch at the top of `renderHome()`:
 *
 *  - manual city:    IANA tz id on record -> real offset, DST-aware.
 *  - manual coords:   no tz on record -> longitude/15 estimate.
 *  - GPS:             device's own current timezone — cross-checked against
 *                      the nearest known city (from [PrayerRecipe.nearestTz]
 *                      / [PrayerRecipe.nearestDistanceKm], synced from the
 *                      WebView's CityDB.nearest() lookup) and overridden
 *                      when they disagree by more than ~25 minutes. This
 *                      catches a phone whose "Automatic date & time" is off
 *                      while GPS itself has moved to a different zone (e.g.
 *                      traveling abroad with manual time still set to
 *                      home) — reproduced here exactly so it's caught at
 *                      boot / the daily tick / a timezone-changed broadcast,
 *                      not only the next time the WebView happens to render.
 */
object TzResolver {

    fun offsetHours(recipe: PrayerRecipe, atMillis: Long): Double {
        val tzId = recipe.tzId
        if (!tzId.isNullOrEmpty()) {
            return TimeZone.getTimeZone(tzId).getOffset(atMillis) / 3600000.0
        }
        if (recipe.locationMode == "manual") {
            return recipe.lon / 15.0
        }

        val deviceTz = TimeZone.getDefault().getOffset(atMillis) / 3600000.0
        val nearestTz = recipe.nearestTz
        val nearestDistanceKm = recipe.nearestDistanceKm
        if (!nearestTz.isNullOrEmpty() && nearestDistanceKm != null && nearestDistanceKm <= 300.0) {
            val locTz = TimeZone.getTimeZone(nearestTz).getOffset(atMillis) / 3600000.0
            if (kotlin.math.abs(locTz - deviceTz) > 0.4) return locTz
        }
        return deviceTz
    }
}
