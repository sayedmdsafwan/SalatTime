package com.safwan.prayertime

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Native port of assets/index.html's `Astro` module — same low-precision
 * solar position equations, same constants, same order of operations.
 * This is NOT a reimplementation from scratch; it is a line-for-line
 * translation, kept deliberately close to the JS so the two stay easy to
 * diff against each other if the JS side is ever tuned.
 *
 * Why this exists natively at all: the WebView/JS side is the source of
 * truth for what's ON SCREEN, but a WebView only runs while the app is
 * open. The home-screen widget needs today's/tomorrow's times to be
 * correct on days the app is never opened — which means Kotlin needs its
 * own copy of the same math, not just a cache of JS's last output. See
 * AlarmScheduler's file doc for how this is used.
 *
 * (An earlier iteration of this codebase deliberately skipped this port
 * as out of scope and instead had the widget repeat the previous day's
 * clock time verbatim. That was fine for the ~1-2min/day drift Bangladesh
 * sees short-term, but silently degrades the longer the app goes
 * unopened and never self-corrects — not acceptable for a prayer-time
 * app. This port replaces that approximation entirely.)
 */
object Astro {

    private const val D2R = Math.PI / 180.0
    private const val R2D = 180.0 / Math.PI

    private fun dsin(d: Double) = sin(d * D2R)
    private fun dcos(d: Double) = cos(d * D2R)
    private fun dtan(d: Double) = tan(d * D2R)
    private fun darcsin(x: Double) = asin(x) * R2D
    private fun darccos(x: Double) = acos(x.coerceIn(-1.0, 1.0)) * R2D
    private fun darccot(x: Double) = atan2(1.0, x) * R2D
    private fun fixAngle(a: Double): Double {
        val v = a - 360.0 * floor(a / 360.0)
        return if (v < 0) v + 360.0 else v
    }

    /** hours-since-midnight, wrapped into [0, 24). */
    fun fixHour(a: Double): Double {
        val v = a - 24.0 * floor(a / 24.0)
        return if (v < 0) v + 24.0 else v
    }

    private fun julianDate(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private data class SunPos(val decl: Double, val eqt: Double)

    private fun sunPosition(jd: Double): SunPos {
        val dd = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * dd)
        val q = fixAngle(280.459 + 0.98564736 * dd)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * dd
        val ra = atan2(dcos(e) * dsin(l), dcos(l)) * R2D / 15
        val eqt = q / 15 - fixHour(ra)
        val decl = darcsin(dsin(e) * dsin(l))
        return SunPos(decl, eqt)
    }

    private fun dhuhrTime(jDate: Double, lng: Double, tz: Double): Double {
        var noon = 12 - lng / 15 + tz
        repeat(2) {
            val jd = jDate + noon / 24 - tz / 24
            val eqt = sunPosition(jd).eqt
            noon = 12 - eqt - lng / 15 + tz
        }
        return noon
    }

    private fun sunAngleTime(
        jDate: Double, lat: Double, lng: Double, tz: Double, angle: Double, ccw: Boolean
    ): Double? {
        val noon = dhuhrTime(jDate, lng, tz)
        val jd = jDate + noon / 24 - tz / 24
        val decl = sunPosition(jd).decl
        val num = -dsin(angle) - dsin(lat) * dsin(decl)
        val den = dcos(lat) * dcos(decl)
        val cosH = num / den
        if (cosH > 1 || cosH < -1) return null
        val h = darccos(cosH) / 15
        return if (ccw) noon - h else noon + h
    }

    private fun asrTime(jDate: Double, lat: Double, lng: Double, tz: Double, factor: Double): Double? {
        val noon = dhuhrTime(jDate, lng, tz)
        val jd = jDate + noon / 24 - tz / 24
        val decl = sunPosition(jd).decl
        val angle = -darccot(factor + dtan(abs(lat - decl)))
        return sunAngleTime(jDate, lat, lng, tz, angle, false)
    }

    /** Mirrors index.html's PRAYER_METHODS table exactly (same angles/minutes). */
    data class Method(val fajrAngle: Double, val ishaAngle: Double? = null, val ishaMinutes: Double? = null)

    val METHODS: Map<String, Method> = mapOf(
        "karachi" to Method(fajrAngle = 18.0, ishaAngle = 18.0),
        "mwl" to Method(fajrAngle = 18.0, ishaAngle = 17.0),
        "isna" to Method(fajrAngle = 15.0, ishaAngle = 15.0),
        "egypt" to Method(fajrAngle = 19.5, ishaAngle = 17.5),
        "tehran" to Method(fajrAngle = 17.7, ishaAngle = 14.0),
        // Mirrors index.html's split: rather than auto-detecting Ramadan via
        // an offline Hijri calendar, both Umm al-Qura intervals are exposed
        // as separate selectable methods and the user picks the one that
        // currently applies.
        "makkah90" to Method(fajrAngle = 18.5, ishaMinutes = 90.0),
        "makkah120" to Method(fajrAngle = 18.5, ishaMinutes = 120.0)
    )

    /** hour-of-day floats (0-24), null where the sun angle is never reached
     *  at this latitude/date (high-latitude edge case) and no seasonal
     *  fallback was possible. */
    data class Day(
        val fajr: Double?,
        val sunrise: Double?,
        val dhuhr: Double,
        val asr: Double?,
        val maghrib: Double?,
        val isha: Double?
    )

    fun computeDay(
        year: Int, month: Int, day: Int,
        lat: Double, lon: Double, tz: Double,
        madhhab: String, methodKey: String
    ): Day {
        val jDate = julianDate(year, month, day)
        val factor = if (madhhab == "hanafi") 2.0 else 1.0
        val m = METHODS[methodKey] ?: METHODS.getValue("karachi")
        val sunrise = sunAngleTime(jDate, lat, lon, tz, 0.833, true)
        val maghrib = sunAngleTime(jDate, lat, lon, tz, 0.833, false)
        var fajr = sunAngleTime(jDate, lat, lon, tz, m.fajrAngle, true)
        var isha = if (m.ishaAngle != null) {
            sunAngleTime(jDate, lat, lon, tz, m.ishaAngle, false)
        } else if (maghrib != null && m.ishaMinutes != null) {
            maghrib + m.ishaMinutes / 60.0
        } else null

        if ((fajr == null || isha == null) && sunrise != null && maghrib != null) {
            var nightLength = 24 - (fixHour(maghrib) - fixHour(sunrise))
            if (nightLength <= 0 || nightLength > 24) nightLength = 8.0
            val portion = nightLength / 7.0
            if (fajr == null) fajr = sunrise - portion
            if (isha == null) isha = maghrib + portion
        }

        return Day(
            fajr = fajr,
            sunrise = sunrise,
            dhuhr = dhuhrTime(jDate, lon, tz),
            asr = asrTime(jDate, lat, lon, tz, factor),
            maghrib = maghrib,
            isha = isha
        )
    }

    private const val KAABA_LAT = 21.4225
    private const val KAABA_LON = 39.8262

    /** True-north bearing (0-360°) from (lat, lon) to the Kaaba. Line-for-line
     *  port of index.html's Astro.qiblaBearing — used by the native Qibla
     *  compass (QiblaSensorController) so the bearing native computes
     *  matches the JS-computed one exactly. */
    fun qiblaBearing(lat: Double, lon: Double): Double {
        val dLon = (KAABA_LON - lon) * D2R
        val lat1 = lat * D2R
        val lat2 = KAABA_LAT * D2R
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = atan2(y, x) * R2D
        return fixAngle(brng)
    }

    /** Great-circle distance (km) from (lat, lon) to the Kaaba. Line-for-line
     *  port of index.html's Astro.qiblaDistanceKm. */
    fun qiblaDistanceKm(lat: Double, lon: Double): Double {
        val r = 6371.0
        val dLat = (KAABA_LAT - lat) * D2R
        val dLon = (KAABA_LON - lon) * D2R
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat * D2R) * cos(KAABA_LAT * D2R) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
