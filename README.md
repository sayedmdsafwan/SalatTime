<div align="center">

# 🕌 Salat Time

**Offline prayer times and Qibla direction for Android.**

### <a href="https://sayedmdsafwan.github.io/SalatTime/" target="_blank" rel="noopener noreferrer">**🌐 Live Demo**</a> &nbsp;|&nbsp; ⬇️ [**Download APK**](https://github.com/sayedmdsafwan/SalatTime/releases/latest/download/SalatTime-debug.apk)

![License](https://img.shields.io/badge/license-MIT-1D6B54?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square)
![Offline](https://img.shields.io/badge/works-offline-8C8371?style=flat-square)

</div>

---

Salat Time does two things, offline: shows the five daily prayer times for
wherever you are, and points you toward the Qibla. No ads, no account,
nothing else to dig through.

- <a href="https://sayedmdsafwan.github.io/SalatTime/" target="_blank" rel="noopener noreferrer">🌐 **Try the live demo**</a> — the actual app, running in your browser. Nothing to install.
- ⬇️ **[Download the latest APK](https://github.com/sayedmdsafwan/SalatTime/releases/latest/download/SalatTime-debug.apk)** — sideload it on any Android device.

The demo and the app are the same code: whatever you try in the browser is
exactly what you get after installing.

## 🕋 The two things it does

**Salat Time** — Fajr, Dhuhr, Asr, Maghrib, and Isha, calculated for your
exact location using standard solar-position formulas, and kept current
automatically. No internet connection is ever required to get correct times.

**Qibla Direction** — a live device-compass bearing to the Kaaba using the
great-circle bearing formula, with a static bearing readout as a fallback on
devices without a magnetometer.

## ✨ Everything else, briefly

- **Fully offline** — once loaded, both features work with no signal.
- **No ads, no accounts** — nothing to sign into, nothing else competing for your attention.
- **Two Asr schools** — Hanafi (shadow factor 2) and Shafi'i/Maliki/Hanbali/Ahl al-Hadith (shadow factor 1), switchable in Settings.
- **High-latitude fallback** — where the sun never reaches the Fajr/Isha angle, falls back to the 1/7th-of-night rule.
- **Three ways to set location** — GPS, an offline database of ~1,800 cities, or manual coordinates.
- **Bilingual** — English and বাংলা, switchable anytime.
- **Persistent settings** — language, madhhab, and location are saved to `localStorage`.
- **A single local HTML file** — no build step, no bundler, no framework. Easy to audit, easy to fork.

## 📱 What's in this repository

This repo is an **Android Studio project** wrapping a self-contained web
app, which lives at
[`app/src/main/assets/index.html`](app/src/main/assets/index.html). The
WebView is still the only source of truth for what's ON SCREEN — every
screen, all rendering, i18n, settings, and the offline city database are
plain HTML/CSS/JS in that one file.

The native Kotlin side, though, is no longer just a thin wrapper — it's
grown a real second copy of the prayer-time math (`Astro.kt`) so the
**home-screen widget stays accurate even on days the app is never
opened**, which a WebView alone can't do (it only runs while the app is
in the foreground). Each file below explains its own reasoning in its
header comment — this table is just a map to find the right one, not a
substitute for reading it:

```
├── app/
│   └── src/main/
│       ├── assets/index.html            ← the entire web app (UI + logic; still the source of truth for what's on screen)
│       ├── java/.../
│       │   ├── MainActivity.kt          ← WebView host, geolocation/permission bridging, Qibla overlay positioning
│       │   ├── NativeBridge.kt          ← JS-to-native bridge (window.NativeBridge) — recipe sync, Qibla, Settings calls
│       │   ├── Astro.kt                 ← native port of index.html's solar-position math — same formulas, kept in sync
│       │   ├── PrayerRecipe.kt          ← everything Astro needs to recompute a day (lat/lon/tz/method/offsets/...)
│       │   ├── TzResolver.kt            ← resolves a DST-aware UTC offset for a recipe (manual city/coords/GPS)
│       │   ├── AlarmScheduler.kt        ← the widget's brain: recomputes today+tomorrow, writes prefs, arms all the alarms below
│       │   ├── WidgetUpdater.kt         ← renders the RemoteViews widget from whatever AlarmScheduler last wrote to prefs
│       │   ├── PrayerWidgetProvider.kt  ← AppWidgetProvider — OS-driven widget lifecycle + manual refresh tap
│       │   ├── WidgetTickReceiver.kt    ← fires at each prayer's start so the widget's "running now" row flips live
│       │   ├── DailyRecomputeReceiver.kt← once-daily (~00:02) recompute, for a widget-only user who never opens the app
│       │   ├── TimeChangeReceiver.kt    ← reacts to timezone/date/clock changes so a stale schedule can't linger
│       │   ├── BootReceiver.kt          ← re-arms everything after reboot (AlarmManager alarms don't survive one)
│       │   ├── LocationRefreshScheduler.kt ← Settings > Auto Location Update — background GPS refresh, its own alarm bookkeeping
│       │   ├── LocationRefreshReceiver.kt  ← thin AlarmManager entry point for the above (goAsync for the async fix)
│       │   ├── QiblaSensorController.kt ← real SensorManager compass (rotation-vector fusion + magnetic-declination correction)
│       │   └── QiblaCompassView.kt      ← the native compass dial/needle drawn over the WebView's Qibla screen
│       ├── res/                         ← app icon, theme, widget layout/XML, widget-provider metadata
│       └── AndroidManifest.xml          ← permissions (location incl. background), all receivers/providers registered
├── docs/                                ← this landing page + browser demo (GitHub Pages) — docs/app/index.html mirrors assets/index.html
├── build.gradle, settings.gradle        ← Gradle project files
└── gradle/wrapper/                      ← Gradle wrapper config
```

**Why two copies of the prayer-math exist:** `assets/index.html`'s JS
`Astro` module and `Astro.kt` compute the exact same thing from the exact
same formulas — deliberately kept as a close line-for-line port rather
than diverging implementations, specifically so they never disagree. If
you change one, change the other the same way and diff them against each
other.

## 🛠 Building the app

**Requirements:** Android Studio (Koala or newer recommended), JDK 17
(bundled with recent Android Studio).

1. Clone this repo.
2. Open the project root folder in Android Studio (**File → Open**).
3. Let Gradle sync. On first sync Android Studio will download Gradle 8.6
   and the Android Gradle Plugin automatically — this needs an internet
   connection once.
   - If Android Studio warns about a missing Gradle wrapper jar, click
     **"Sync Project with Gradle Files"** (elephant icon in the toolbar), or
     go to **File → Settings → Build Tools → Gradle** and let it regenerate
     the wrapper — this is a one-time step and is expected, since the binary
     wrapper jar isn't committed to this repo (see `.gitignore`).
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)**, or just press ▶ Run
   with a device/emulator connected.

The generated debug APK lands in `app/build/outputs/apk/debug/`.

No signing config is included — for a release build, configure your own
keystore in `app/build.gradle` before running `assembleRelease`.

## 🔐 Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Used when you tap "Use Current Location (GPS)". Never sent anywhere — used purely on-device for the prayer time / Qibla math. Either is enough — coarse location doesn't meaningfully affect prayer-time accuracy. |
| `ACCESS_BACKGROUND_LOCATION` | Optional — only requested if you turn on Settings > Auto Location Update, so the widget can silently re-check GPS while the app is closed (e.g. while traveling). The app works exactly the same without granting this; that feature just no-ops. |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Only used to load the Google Fonts stylesheet. If unavailable, the UI falls back to system fonts and everything else keeps working fully offline. |

No analytics SDKs, no ads, nothing phoned home. The app does now run a
handful of `AlarmManager`-scheduled `BroadcastReceiver`s in the
background (see the file table above) — that's what keeps the
home-screen widget accurate on days you never open the app, and, if you
opt in, what powers Auto Location Update. None of it needs a persistent
foreground service; each receiver wakes briefly, does its one job, and
goes back to sleep.

## 🧮 Prayer time methodology

- Solar declination and the equation of time are derived from the Julian date using standard approximation formulas.
- **Fajr angle:** 18° below horizon · **Isha angle:** 17° below horizon (Muslim World League convention).
- **Sunrise/Maghrib:** standard 0.833° horizon dip.
- **Asr:** shadow-length method, factor 1 or 2.

This is a general-purpose calculation and, like any astronomical method, may
differ by a minute or two from your local mosque's published timetable —
always defer to local authorities for exact timing where it matters.

## 🤝 Contributing

Issues and pull requests are welcome — additional cities, more languages,
calculation-method options (ISNA, Umm al-Qura, etc.), or general bug fixes.
UI/screen/settings changes are usually just `app/src/main/assets/index.html`.
Anything touching the widget, background scheduling, or the Qibla compass
also needs the matching native Kotlin file (see the file table above) —
and if it touches the prayer-time formulas themselves, both `Astro`
implementations (JS and `Astro.kt`) need the same change, kept in sync on
purpose.

## 📄 License

MIT — see [LICENSE](LICENSE). Use it, fork it, ship it.
