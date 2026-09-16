// Regression test for the prayer-time math (index.html's Astro module /
// Astro.kt). Run this with:  node tools/verify_prayer_times.js
//
// WHAT THIS DOES: checks computeDay() against a fixed table of golden
// values across several latitudes, hemispheres, methods and madhhabs
// (including a high-latitude case that exercises the polar-day fallback).
// The goldens were generated FROM this same algorithm on 2026-09-15 —
// this is a regression/snapshot test, not independent ground truth. Its
// job is to catch an ACCIDENTAL change to the math (a typo in a formula,
// a wrong constant, a reordered step) the moment you make it, before it
// ships to real users who rely on this for prayer times.
//
// WHEN TO RUN: any time you (or an AI) touch the Astro engine in
// index.html or Astro.kt. If a change to the math is intentional and
// verified correct (e.g. against a real published timetable), re-run
// tools/gen_cases.js to regenerate GOLDEN below and update it deliberately
// — never just to make a failing test pass without checking why it failed.
//
// NOTE: this only tests the JS engine directly (astro_engine.js, a copy
// kept in sync with index.html — see its own header comment). It does
// NOT run the Kotlin side. AstroTest.kt (app/src/test/java/.../AstroTest.kt)
// tests Kotlin against this SAME golden table — if both pass, JS and
// Kotlin agree with each other by construction, which is the real point:
// that's what catches the two engines silently drifting apart.
const { computeDay } = require('./astro_engine.js');

const TOLERANCE_HOURS = 2 / 60; // 2 minutes — generous enough to survive
                                  // minor float/rounding differences,
                                  // tight enough to catch a real formula bug.

const CASES = [
  { name:"dhaka_karachi_hanafi",  lat:23.8103,  lon:90.4125,   tz:6,   y:2026,m:9,d:15,  madhhab:"hanafi", method:"karachi",
    expect:{fajr:4.481508087128063, sunrise:5.7446113995944, dhuhr:11.894023441171047, asr:16.318777084086822, maghrib:18.043435482747693, isha:19.30653879521403} },
  { name:"dhaka_solstice_summer", lat:23.8103,  lon:90.4125,   tz:6,   y:2026,m:6,d:21,  madhhab:"hanafi", method:"karachi",
    expect:{fajr:3.7343668903260294, sunrise:5.199361131193647, dhuhr:12.00203838717253, asr:16.676514548767386, maghrib:18.80471564315141, isha:20.26970988401903} },
  { name:"dhaka_solstice_winter", lat:23.8103,  lon:90.4125,   tz:6,   y:2026,m:12,d:21, madhhab:"hanafi", method:"karachi",
    expect:{fajr:5.258972714715625, sunrise:6.605936326378042, dhuhr:11.938119869054361, asr:15.672471994298329, maghrib:17.27030341173068, isha:18.617267023393097} },
  { name:"mecca_makkah90_shafi",  lat:21.4225,  lon:39.8262,   tz:3,   y:2026,m:3,d:1,   madhhab:"shafi",  method:"makkah90",
    expect:{fajr:5.41837047883874, sunrise:6.687969873281669, dhuhr:12.550467268458684, asr:15.905683559769486, maghrib:18.412964663635698, isha:19.912964663635698} },
  { name:"london_mwl_shafi",      lat:51.5074,  lon:-0.1278,   tz:0,   y:2026,m:1,d:15,  madhhab:"shafi",  method:"mwl",
    expect:{fajr:5.98309367957207, sunrise:7.98818136942298, dhuhr:12.165345438310037, asr:14.019974209713153, maghrib:16.342509507197093, isha:18.238066176437414} },
  { name:"newyork_isna_shafi",    lat:40.7128,  lon:-74.0060,  tz:-5,  y:2026,m:1,d:15,  madhhab:"shafi",  method:"isna",
    expect:{fajr:5.957904534277181, sunrise:7.296626575471105, dhuhr:12.091752428010047, asr:14.567253883852814, maghrib:16.886878280548988, isha:18.225600321742913} },
  { name:"sydney_mwl_shafi",      lat:-33.8688, lon:151.2093,  tz:11,  y:2026,m:6,d:21,  madhhab:"shafi",  method:"mwl",
    expect:{fajr:6.509250807072774, sunrise:7.99994564518368, dhuhr:12.948305332629683, asr:15.598067738897027, maghrib:17.896665020075687, isha:19.303447018639396} },
  { name:"reykjavik_highlat_mwl", lat:64.1466,  lon:-21.9426,  tz:0,   y:2026,m:6,d:21,  madhhab:"shafi",  method:"mwl",
    expect:{fajr:1.7790875346176658, sunrise:2.9219446774748086, dhuhr:13.4935106856271, asr:18.374592786855132, maghrib:24.065076693779393, isha:25.207933836636535} },
];

let failures = 0;
for (const c of CASES) {
  const got = computeDay(c.y, c.m, c.d, c.lat, c.lon, c.tz, c.madhhab, c.method);
  for (const key of ["fajr","sunrise","dhuhr","asr","maghrib","isha"]) {
    const g = got[key], e = c.expect[key];
    if (g === null || e === null) {
      if (g !== e) { console.log(`FAIL ${c.name}.${key}: got ${g}, expected ${e}`); failures++; }
      continue;
    }
    if (Math.abs(g - e) > TOLERANCE_HOURS) {
      console.log(`FAIL ${c.name}.${key}: got ${g.toFixed(4)}h, expected ${e.toFixed(4)}h (diff ${(Math.abs(g-e)*60).toFixed(1)}min)`);
      failures++;
    }
  }
}

if (failures === 0) {
  console.log(`PASS — all ${CASES.length} cases within ${TOLERANCE_HOURS*60}min tolerance.`);
  process.exit(0);
} else {
  console.log(`\n${failures} check(s) failed.`);
  process.exit(1);
}
