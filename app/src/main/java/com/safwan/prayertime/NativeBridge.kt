package com.safwan.prayertime

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JS-to-native bridge for the home-screen widget's data. Injected into
 * the WebView as `window.NativeBridge` (see MainActivity.setupWebView).
 * Every method here is called on a background thread by the WebView, per
 * the JavascriptInterface contract.
 *
 * Counterpart on the JS side: index.html's syncPrayerDataToNative().
 */
class NativeBridge(private val activity: MainActivity) {

    /** payload shape (see index.html syncPrayerDataToNative()) — a
     * RECIPE, not precomputed times. Native derives today's/tomorrow's
     * times itself via [Astro] from this, so the same recipe can be
     * recomputed again on any later day without JS's involvement:
     * {
     *   timeFormat: "12"|"24", lang: "en"|"bn",
     *   lat: number, lon: number,
     *   tz: string|null,            // IANA id, or null
     *   locationMode: "gps"|"manual"|null,
     *   madhhab: "hanafi"|..., calcMethod: "karachi"|...,
     *   offsets: {fajr,dhuhr,asr,maghrib,isha: {start:number,end:number}},  // minutes
     *   nearestTz: string|null,          // nearest known city's IANA id
     *   nearestDistanceKm: number|null   // distance to that city, km
     * }
     */
    @JavascriptInterface
    fun syncPrayerData(json: String) {
        try {
            val obj = JSONObject(json)

            val offsetsObj = obj.optJSONObject("offsets") ?: JSONObject()
            val offsets = AlarmScheduler.PRAYER_KEYS.associateWith { key ->
                val o = offsetsObj.optJSONObject(key)
                PrayerOffset(start = o?.optDouble("start", 0.0) ?: 0.0, end = o?.optDouble("end", 0.0) ?: 0.0)
            }

            if (!obj.has("lat") || !obj.has("lon") || obj.isNull("lat") || obj.isNull("lon")) {
                // No location on record yet (e.g. onboarding not finished) —
                // nothing to compute; skip rather than write a bogus recipe.
                return
            }

            val recipe = PrayerRecipe(
                lat = obj.getDouble("lat"),
                lon = obj.getDouble("lon"),
                tzId = if (obj.isNull("tz")) null else obj.optString("tz", "").ifEmpty { null },
                locationMode = if (obj.isNull("locationMode")) null else obj.optString("locationMode", "").ifEmpty { null },
                madhhab = obj.optString("madhhab", "hanafi"),
                calcMethod = obj.optString("calcMethod", "karachi"),
                offsets = offsets,
                lang = obj.optString("lang", "en"),
                timeFormat = obj.optString("timeFormat", "12"),
                nearestTz = if (obj.isNull("nearestTz")) null else obj.optString("nearestTz", "").ifEmpty { null },
                nearestDistanceKm = if (obj.isNull("nearestDistanceKm") || !obj.has("nearestDistanceKm")) null
                    else obj.optDouble("nearestDistanceKm", Double.NaN).let { if (it.isNaN()) null else it }
            )

            AlarmScheduler.syncRecipeAndSchedule(activity, recipe)
        } catch (_: Exception) {
            // Malformed/partial payload — best-effort, skip this sync rather
            // than crash; the next renderHome() 30s tick will retry.
        }
    }

    /** Settings > Auto Location Update — see [LocationRefreshScheduler].
     *  [timesPerDay] must be one of {1,6,12,24}; anything else is
     *  clamped to the nearest allowed value. */
    @JavascriptInterface
    fun setLocationRefreshFrequency(timesPerDay: Int) {
        LocationRefreshScheduler.setFrequency(activity, timesPerDay)
    }

    /** Settings > Auto Location Update — whether Android's background
     *  location permission is currently granted, so the Settings screen
     *  can show a "grant permission" prompt instead of silently doing
     *  nothing whenever the background tick fires. */
    @JavascriptInterface
    fun hasBackgroundLocationPermission(): Boolean =
        LocationRefreshScheduler.hasBackgroundLocationPermission(activity)

    /** Settings > Auto Location Update — launches Android's own
     *  "Allow all the time" background-location request. Must run on
     *  the UI thread since it starts a permission-request flow. */
    @JavascriptInterface
    fun requestBackgroundLocationPermission() {
        activity.runOnUiThread { activity.requestBackgroundLocation() }
    }

    /** Called from index.html's startCompassNative()/stopCompassNative()
     *  whenever the Qibla screen becomes visible/hidden (and also whenever
     *  the Qibla screen re-enters with a possibly-changed dark-mode state,
     *  since JS re-sends this on every startCompass()). [dark] mirrors
     *  index.html's `state.darkMode` (an in-app toggle, not the system
     *  theme) at the moment the screen opened. */
    @JavascriptInterface
    fun qiblaVisible(visible: Boolean, dark: Boolean) {
        activity.runOnUiThread { activity.setQiblaVisible(visible, dark) }
    }

    /** Called from index.html's sendQiblaRect() — the on-screen position/
     *  size (CSS px, i.e. WebView content px at this app's fixed
     *  initial-scale=1.0/device-width viewport) of #qibla-compass-stack,
     *  so the native overlay can be positioned exactly on top of it. Fired
     *  once when the Qibla screen opens and again on every `resize` event
     *  while it's open (rotation, font-scale change, etc.). */
    @JavascriptInterface
    fun qiblaRingRect(left: Float, top: Float, width: Float, height: Float) {
        activity.runOnUiThread { activity.setQiblaRingRect(left, top, width, height) }
    }
}
