<div align="center">

# 🕌 Salat Time

**Prayer times and the Qibla direction, worked out on your phone — no signal needed.**

<img alt="Platform" src="https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white">
<img alt="min SDK" src="https://img.shields.io/badge/min%20SDK-21%20(Android%205.0)-1D6B54?style=flat-square">
<img alt="License" src="https://img.shields.io/badge/license-MIT-1D6B54?style=flat-square">
<img alt="Offline" src="https://img.shields.io/badge/works-100%25%20offline-8C8371?style=flat-square">
<img alt="Latest release" src="https://img.shields.io/github/v/release/sayedmdsafwan/SalatTime?style=flat-square&color=1D6B54">

<br><br>

<a href="https://github.com/sayedmdsafwan/SalatTime/releases/latest/download/SalatTime-debug.apk">
  <img alt="Download the latest APK" src="https://img.shields.io/badge/%E2%AC%87%20Download%20APK-1D6B54?style=for-the-badge&logo=android&logoColor=white" height="42">
</a>

<br>

<a href="https://sayedmdsafwan.github.io/SalatTime/"><strong>🌐 Try the live demo</strong></a> · [All releases](https://github.com/sayedmdsafwan/SalatTime/releases) · [Report a bug](https://github.com/sayedmdsafwan/SalatTime/issues)

</div>

---

Salat Time does two things, offline: it shows the five daily prayer times for wherever you
are, and it points you towards the Qibla. No ads, no account, nothing else competing for
your attention.

The demo and the app are the same code — whatever you try in the browser is exactly what you
get after installing.

## ✨ Highlights

- **Fully offline** — the times and the Qibla bearing are computed on the device from solar position. No connection is ever needed to get them right.
- **A home-screen widget that stays right** — it recomputes today's and tomorrow's times natively, so it is accurate on days you never open the app. The refresh button really refreshes.
- **Two Asr schools, six calculation methods** — Hanafi or Shafi'i/Maliki/Hanbali/Ahl al-Hadith, plus Karachi, MWL, ISNA, Egypt, Tehran and Umm al-Qura timings.
- **Three ways to set your location** — GPS, an offline database of ~1,800 cities, or coordinates typed by hand.
- **Bilingual** — English and বাংলা, switchable at any time.
- **One HTML file** — no framework, no bundler, easy to audit and easy to fork.

## 🕋 The two things it does

**Prayer times.** Fajr, Dhuhr, Asr, Maghrib and Isha, plus sunrise and sunset, calculated
from standard solar-position formulas and kept current automatically. Where the sun never
reaches the Fajr or Isha angle — high latitudes in summer — the app falls back to the
one-seventh-of-the-night rule instead of showing nothing.

**Qibla direction.** A live compass bearing to the Kaaba using the great-circle formula,
with magnetic declination corrected so the needle points at true north. On a device without
a magnetometer, it falls back to a static bearing you can read off and follow on any compass.

## 📲 Install

1. Open the [latest APK](https://github.com/sayedmdsafwan/SalatTime/releases/latest/download/SalatTime-debug.apk) on your Android phone.
2. If Android asks, allow **Install unknown apps** for your browser or file manager.
3. Tap **Install**, then open **Salat Time**.

> This is a debug build published for easy testing, so Play Protect may show a warning.
> The app has no analytics and no network SDKs; the full source is in this repository.

## 🧭 Features in detail

### Prayer times
- Five daily prayers with sunrise and sunset, plus the prohibited times and the optional nafl prayers.
- **No hidden buffer.** A displayed time is exactly what the calculation gives — nothing is added for you. If you want a safety margin, set it yourself per prayer in **Settings → Add Buffer Time** (both start and end, in minutes, any sign; the default is 0).
- Two Asr schools: Hanafi (shadow factor 2) and Shafi'i/Maliki/Hanbali/Ahl al-Hadith (shadow factor 1).
- Six calculation methods, switchable in Settings; the default is Karachi (18°/18°).

### Location
- **GPS** — a single tap, on device, never sent anywhere.
- **Offline city database** — about 1,800 cities, grouped so Bangladesh is quick to reach.
- **Manual coordinates** — for anywhere the list doesn't cover.
- **Auto location update** (optional) — while you travel, the home-screen widget can re-check GPS in the background. The fix is applied to the app the next time you open it, so the screen and the widget always agree.

### Home-screen widget
- Five rows with the running prayer highlighted, flipping automatically as each waqt begins.
- Times are recomputed natively — for today and tomorrow — on the daily tick, on every boot, after a timezone or clock change, and when you tap refresh.
- Everything runs on ordinary `AlarmManager` alarms and short-lived receivers: no foreground service, no notification permission.

### Interface
- English and বাংলা, along with 12/24-hour time.
- Light and dark themes.
- Settings, location and language are remembered between launches.

## 📱 What's in this repository

The on-screen app lives in a single file,
[`app/src/main/assets/index.html`](app/src/main/assets/index.html) — every screen, all
rendering, i18n, settings and the offline city database are plain HTML/CSS/JS in there.

The native Kotlin side is more than a thin wrapper: it carries a second copy of the
prayer-time maths (`Astro.kt`) so the widget can stay accurate without the WebView running.
Each file explains its own reasoning in a header comment; this table is only a map:

```
├── app/src/main/
│   ├── assets/index.html            ← the entire web app (UI + logic; source of truth for what's on screen)
│   ├── java/.../
│   │   ├── MainActivity.kt          ← WebView host, geolocation/permission bridging, Qibla overlay positioning
│   │   ├── NativeBridge.kt          ← JS-to-native bridge (window.NativeBridge) — recipe sync, Qibla, settings
│   │   ├── Astro.kt                 ← native port of index.html's solar-position maths — same formulas, kept in sync
│   │   ├── PrayerRecipe.kt          ← everything Astro needs to recompute a day (lat/lon/tz/method/offsets/…)
│   │   ├── TzResolver.kt            ← resolves a DST-aware UTC offset for a recipe (manual city/coords/GPS)
│   │   ├── AlarmScheduler.kt        ← the widget's brain: recomputes today+tomorrow, writes prefs, arms the alarms
│   │   ├── WidgetUpdater.kt         ← renders the RemoteViews widget from what AlarmScheduler last wrote
│   │   ├── PrayerWidgetProvider.kt  ← AppWidgetProvider — OS lifecycle + the refresh tap (recomputes, then redraws)
│   │   ├── WidgetTickReceiver.kt    ← fires at each prayer's start so the "running now" row flips live
│   │   ├── DailyRecomputeReceiver.kt← once-daily (~00:02) recompute for a widget-only user
│   │   ├── TimeChangeReceiver.kt    ← reacts to timezone/date/clock changes so a stale schedule can't linger
│   │   ├── BootReceiver.kt          ← re-arms everything after a reboot
│   │   ├── LocationRefreshScheduler.kt / LocationRefreshReceiver.kt ← optional background GPS refresh
│   │   ├── QiblaSensorController.kt ← SensorManager compass (rotation-vector fusion + declination correction)
│   │   └── QiblaCompassView.kt      ← the native compass dial and needle over the WebView's Qibla screen
│   ├── res/                         ← icon, theme, widget layout, widget-provider metadata
│   └── AndroidManifest.xml          ← permissions (location incl. background), receivers/providers
├── docs/                            ← landing page + browser demo (GitHub Pages); docs/app/index.html mirrors assets/index.html
├── tools/                           ← developer-only prayer-maths regression tests (not part of the app)
├── app/src/test/                    ← AstroTest.kt, the Kotlin half of the same tests
└── build.gradle, settings.gradle, gradle/wrapper/
```

**Why the maths exists twice:** the JS `Astro` module and `Astro.kt` compute the same thing
from the same formulas, deliberately kept as a line-for-line port so they can never
disagree. Change one, change the other the same way and diff them against each other.

## 🛠 Building the app

**Requirements:** [Android Studio](https://developer.android.com/studio) (Koala or newer),
JDK 17 (the embedded JDK works).

```bash
git clone https://github.com/sayedmdsafwan/SalatTime.git
cd SalatTime
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/
```

On Windows use `gradlew.bat`. The Gradle wrapper (scripts and jar) is committed, so a
command-line build works straight after cloning; the first run downloads Gradle 8.14.5 and
the Android Gradle Plugin, which needs an internet connection once. In Android Studio:
**File → Open** → select the folder, let Gradle sync, then press **Run ▶**.

No signing config is included — configure your own keystore before `assembleRelease`.

## 🔐 Permissions

| Permission | Why |
| --- | --- |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Used when you tap "Use Current Location (GPS)". Never sent anywhere. Either is enough — coarse location barely affects prayer-time accuracy. |
| `ACCESS_BACKGROUND_LOCATION` | Optional. Only requested if you turn on Settings → Auto Location Update, so the widget can re-check GPS while the app is closed. Without it the app behaves exactly the same; that feature simply does nothing. |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Only used to load the Google Fonts stylesheet. If unavailable, the UI falls back to system fonts and everything else keeps working offline. |
| `RECEIVE_BOOT_COMPLETED` | Alarms don't survive a reboot, so the widget re-arms them on start-up. |

No analytics SDKs, no ads, nothing phoned home. The app does run a handful of
`AlarmManager`-scheduled receivers in the background — that is what keeps the widget
accurate on days you never open the app, and what powers Auto Location Update if you opt in.
Each one wakes briefly, does its one job, and goes back to sleep.

## 🧮 Prayer time methodology

- Solar declination and the equation of time are derived from the Julian date using standard approximation formulas.
- **Default method:** Karachi — Fajr 18°, Isha 18°. Also available: MWL (18°/17°), ISNA (15°/15°), Egyptian (19.5°/17.5°), Tehran (17.7°/14°) and Umm al-Qura (18.5°, Isha 90 or 120 minutes after Maghrib).
- **Sunrise / Maghrib:** standard 0.833° horizon dip.
- **Asr:** shadow-length method, factor 1 or 2 depending on the school.
- **High latitudes:** when the sun never reaches the Fajr/Isha angle, the one-seventh-of-the-night rule is used.

This is a general-purpose calculation and, like any astronomical method, may differ by a
minute or two from your local mosque's timetable — always defer to local authorities where
it matters.

## 🧪 Verifying the maths (optional, developer-only)

A safety net for anyone changing the calculation — it changes nothing about building or
using the app.

Because the formulas exist in two places (JS and `Astro.kt`), a small mistake in either
could shift a prayer by a few minutes without anyone noticing. These tests catch that:

- `tools/verify_prayer_times.js` — checks the JS maths against a fixed table of known values for 8 city/date pairs, including a high-latitude case.
- `app/src/test/java/com/safwan/prayertime/AstroTest.kt` — checks `Astro.kt` against the exact same table.
- `tools/gen_cases.js` — regenerates the table if you deliberately change the maths.

```bash
node tools/verify_prayer_times.js   # needs Node.js, nothing else
./gradlew test                      # plain JVM test, no emulator needed
```

Both should end in `PASS` / `BUILD SUCCESSFUL`. If you change the maths on purpose, update
`Astro` in `index.html` and `Astro.kt` together, regenerate the cases, verify the new
numbers against a real timetable, and update the golden values in **both** test files.

## 🤝 Contributing

Issues and pull requests are welcome — additional cities, more languages, more calculation
methods, or bug fixes. UI, screen and settings changes are usually just
`app/src/main/assets/index.html`. Anything touching the widget, background scheduling or the
Qibla compass also needs the matching Kotlin file (see the table above), and anything
touching the prayer-time formulas needs both `Astro` implementations changed in step.

## 📄 License

MIT — see [LICENSE](LICENSE). Use it, fork it, ship it.

---

<div align="center">
<sub>Made by <strong>Md. Safwan</strong> · Also on GitHub: <a href="https://github.com/sayedmdsafwan/Fuel-Metrics">Fuel Metrics</a> · <a href="https://github.com/sayedmdsafwan/SafwanExp">Safwan Exp</a></sub>
</div>
