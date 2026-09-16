package com.safwan.prayertime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for [Astro.computeDay] — mirrors tools/verify_prayer_times.js
 * EXACTLY (same cases, same golden values, same tolerance). See that file's
 * header comment for the full rationale: these are snapshot/regression
 * values (generated FROM this same algorithm), not independent ground
 * truth — their job is to catch an accidental change to the math, and
 * because Kotlin and JS are both checked against the identical numbers
 * here, a pass on both sides also means the two engines still agree with
 * each other.
 *
 * Run with: ./gradlew test   (or ./gradlew testDebugUnitTest)
 * No emulator/device needed — Astro.kt has no Android dependency, so this
 * runs as a plain JVM unit test in a couple seconds.
 *
 * If you change the math in Astro.kt (or index.html's Astro module, which
 * this must stay identical to), re-run tools/gen_cases.js after verifying
 * the new numbers are correct, and update BOTH this file and
 * verify_prayer_times.js's golden tables together — never one without the
 * other, or the two engines can silently drift apart again.
 */
class AstroTest {

    private val toleranceHours = 2.0 / 60.0 // 2 minutes

    private data class Case(
        val name: String, val lat: Double, val lon: Double, val tz: Double,
        val y: Int, val m: Int, val d: Int, val madhhab: String, val method: String,
        val expect: Astro.Day
    )

    private val cases = listOf(
        Case("dhaka_karachi_hanafi", 23.8103, 90.4125, 6.0, 2026, 9, 15, "hanafi", "karachi",
            Astro.Day(4.481508087128063, 5.7446113995944, 11.894023441171047, 16.318777084086822, 18.043435482747693, 19.30653879521403)),
        Case("dhaka_solstice_summer", 23.8103, 90.4125, 6.0, 2026, 6, 21, "hanafi", "karachi",
            Astro.Day(3.7343668903260294, 5.199361131193647, 12.00203838717253, 16.676514548767386, 18.80471564315141, 20.26970988401903)),
        Case("dhaka_solstice_winter", 23.8103, 90.4125, 6.0, 2026, 12, 21, "hanafi", "karachi",
            Astro.Day(5.258972714715625, 6.605936326378042, 11.938119869054361, 15.672471994298329, 17.27030341173068, 18.617267023393097)),
        Case("mecca_makkah90_shafi", 21.4225, 39.8262, 3.0, 2026, 3, 1, "shafi", "makkah90",
            Astro.Day(5.41837047883874, 6.687969873281669, 12.550467268458684, 15.905683559769486, 18.412964663635698, 19.912964663635698)),
        Case("london_mwl_shafi", 51.5074, -0.1278, 0.0, 2026, 1, 15, "shafi", "mwl",
            Astro.Day(5.98309367957207, 7.98818136942298, 12.165345438310037, 14.019974209713153, 16.342509507197093, 18.238066176437414)),
        Case("newyork_isna_shafi", 40.7128, -74.0060, -5.0, 2026, 1, 15, "shafi", "isna",
            Astro.Day(5.957904534277181, 7.296626575471105, 12.091752428010047, 14.567253883852814, 16.886878280548988, 18.225600321742913)),
        Case("sydney_mwl_shafi", -33.8688, 151.2093, 11.0, 2026, 6, 21, "shafi", "mwl",
            Astro.Day(6.509250807072774, 7.99994564518368, 12.948305332629683, 15.598067738897027, 17.896665020075687, 19.303447018639396)),
        Case("reykjavik_highlat_mwl", 64.1466, -21.9426, 0.0, 2026, 6, 21, "shafi", "mwl",
            Astro.Day(1.7790875346176658, 2.9219446774748086, 13.4935106856271, 18.374592786855132, 24.065076693779393, 25.207933836636535))
    )

    @Test
    fun computeDay_matchesGoldenValues() {
        for (c in cases) {
            val got = Astro.computeDay(c.y, c.m, c.d, c.lat, c.lon, c.tz, c.madhhab, c.method)
            assertClose("${c.name}.fajr", c.expect.fajr, got.fajr)
            assertClose("${c.name}.sunrise", c.expect.sunrise, got.sunrise)
            assertClose("${c.name}.dhuhr", c.expect.dhuhr, got.dhuhr)
            assertClose("${c.name}.asr", c.expect.asr, got.asr)
            assertClose("${c.name}.maghrib", c.expect.maghrib, got.maghrib)
            assertClose("${c.name}.isha", c.expect.isha, got.isha)
        }
    }

    private fun assertClose(label: String, expected: Double?, actual: Double?) {
        if (expected == null || actual == null) {
            assertEquals(label, expected, actual)
            return
        }
        val diffMinutes = Math.abs(expected - actual) * 60.0
        assertTrue(
            "$label: expected ${expected}h, got ${actual}h (diff ${"%.1f".format(diffMinutes)}min)",
            Math.abs(expected - actual) <= toleranceHours
        )
    }
}
