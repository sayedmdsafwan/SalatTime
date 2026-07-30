<div align="center">

# 🕌 Salat Time

**Offline prayer times and Qibla direction for Android.**

### 🌐 [**Live Demo**](https://sayedmdsafwan.github.io/Salat-Time/) &nbsp;|&nbsp; ⬇️ [**Download APK**](https://github.com/sayedmdsafwan/Salat-Time/releases/latest/download/SalatTime-debug.apk)

![License](https://img.shields.io/badge/license-MIT-1D6B54?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square)
![Offline](https://img.shields.io/badge/works-offline-8C8371?style=flat-square)

</div>

---

Salat Time does two things, offline: shows the five daily prayer times for
wherever you are, and points you toward the Qibla. No ads, no account,
nothing else to dig through.

- 🌐 **[Try the live demo](https://sayedmdsafwan.github.io/Salat-Time/)** — the actual app, running in your browser. Nothing to install.
- ⬇️ **[Download the latest APK](https://github.com/sayedmdsafwan/Salat-Time/releases/latest/download/SalatTime-debug.apk)** — sideload it on any Android device.

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

This repo is an **Android Studio project** — a thin native wrapper
(`MainActivity.kt`, ~100 lines) around the web app, which lives untouched at
[`app/src/main/assets/index.html`](app/src/main/assets/index.html). The
native shell exists only to:

1. Load `index.html` from local assets (`file:///android_asset/index.html`),
   with JavaScript and DOM storage (`localStorage`) enabled.
2. Bridge Android's runtime location permission dialog to the web app's own
   `navigator.geolocation` prompt, so GPS mode works like it would in a
   regular browser.

Everything else — screens, rendering, prayer math, i18n, the offline city
database — is plain HTML/CSS/JS inside that one file.

```
├── app/
│   └── src/main/
│       ├── assets/index.html        ← the entire web app (UI + logic)
│       ├── java/.../MainActivity.kt ← WebView host + geolocation bridge
│       ├── res/                     ← app icon, theme, single-WebView layout
│       └── AndroidManifest.xml      ← permissions (location, internet)
├── docs/                            ← this landing page + browser demo (GitHub Pages)
├── build.gradle, settings.gradle    ← Gradle project files
└── gradle/wrapper/                  ← Gradle wrapper config
```

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
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Only used when you tap "Use Current Location (GPS)". Never sent anywhere — used purely on-device for the prayer time / Qibla math. |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Only used to load the Google Fonts stylesheet. If unavailable, the UI falls back to system fonts and everything else keeps working fully offline. |

No other permissions, no background services, no analytics SDKs.

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
Since the whole app is one HTML file, most changes only require editing
`app/src/main/assets/index.html`.

## 📄 License

MIT — see [LICENSE](LICENSE). Use it, fork it, ship it.
