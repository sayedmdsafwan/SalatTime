package com.safwan.prayertime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

/**
 * Thin native shell around the offline Salat & Qibla web app.
 *
 * All product logic (prayer time math, Qibla bearing, city database,
 * settings, i18n) lives in assets/index.html and runs entirely inside the
 * WebView. This class bridges the things the web app cannot do on its own
 * from inside a WebView sandbox:
 *
 *  1. Android's runtime location permission — the web app's own
 *     `navigator.geolocation.getCurrentPosition()` call surfaces here as
 *     [WebChromeClient.onGeolocationPermissionsShowPrompt]; we ask Android
 *     for permission and only then answer the web app's prompt.
 *  2. Loading the local asset and turning on JS/DOM storage, which are off
 *     by default for a raw WebView.
 *  3. The home-screen widget's data — via [NativeBridge], addJavascriptInterface'd
 *     in as `NativeBridge` (see index.html's syncPrayerDataToNative()).
 *  4. The Qibla compass — index.html's startCompassNative()/stopCompassNative()
 *     (see index.html's Qibla Compass JS) hand off to [QiblaSensorController]
 *     + [QiblaCompassView], a real SensorManager-driven compass overlaid
 *     exactly on top of the WebView's `#qibla-compass-stack` element via
 *     [qiblaOverlay]. Position/size come from [NativeBridge.qiblaRingRect];
 *     visibility/theme from [NativeBridge.qiblaVisible].
 */
class MainActivity : AppCompatActivity(), QiblaSensorController.Listener {

    lateinit var webView: WebView
        private set

    private lateinit var qiblaOverlay: FrameLayout
    private lateinit var qiblaCompassView: QiblaCompassView
    private lateinit var qiblaSensorController: QiblaSensorController

    // Whether the Qibla screen is the currently-active WebView screen, per
    // the last NativeBridge.qiblaVisible() call — kept separately from
    // whether the sensor is actually registered right now, since onPause()/
    // onResume() stop/restart the sensor independently of this (see the
    // "app backgrounded" case in the class doc above).
    private var qiblaScreenVisible = false
    private var qiblaShowingWorking = false

    // Held while we wait on the Android runtime permission dialog so we can
    // resume the web app's own geolocation prompt once the user answers.
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        setupWebView()

        qiblaOverlay = findViewById(R.id.qibla_overlay)
        qiblaCompassView = QiblaCompassView(this)
        qiblaOverlay.addView(
            qiblaCompassView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        // Belt-and-suspenders alongside activity_main.xml's own 0dp default
        // (see that file's comment) — the overlay's child is MATCH_PARENT,
        // so it must never be measured while ambiguously sized.
        qiblaOverlay.layoutParams = FrameLayout.LayoutParams(0, 0)
        qiblaSensorController = QiblaSensorController(this, this)

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html")
        }

        // Modern replacement for the deprecated onBackPressed() override.
        // The web app pushes a browser-history entry whenever it navigates
        // into settings/onboarding/city-picker (see index.html), so
        // webView.canGoBack() is true while inside that flow and false at
        // the home/qibla base tabs. Respect that: step back inside the web
        // app first, and only let the system handle back (closing the app)
        // once there's nothing left to unwind. This covers both the back
        // button and the Android 13+ predictive-back gesture, since both
        // route through this same dispatcher.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // required for localStorage (saved settings/location)
            setGeolocationEnabled(true)
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

            // Accessibility (item 7): respect the system "Font size" setting
            // instead of rendering the WebView content at a fixed size
            // regardless of it. textZoom is a percentage; clamped so an
            // extreme system setting doesn't blow the layout apart before
            // the CSS-side overflow protections (see index.html) even get
            // a chance to help.
            textZoom = (resources.configuration.fontScale * 100f)
                .toInt()
                .coerceIn(100, 150)
        }

        // The app is a single local page (assets/index.html) with one
        // deliberate exception: the "See Reference" link on the Prohibited
        // Times card, which points at an external hadith source. Anything
        // http(s) is handed off to the system browser via ACTION_VIEW so it
        // never replaces the app's own page inside this WebView (which has
        // no address bar or back-out affordance); the local asset itself
        // still loads through the default in-WebView path.
        //
        // Both overloads are implemented: the WebResourceRequest one is
        // what API 24+ actually calls, but minSdk here is 21, and on
        // 21–23 the system only ever calls the deprecated String-URL
        // overload — silently skipping it would mean external links keep
        // opening in-WebView on those OS versions.
        webView.webViewClient = object : WebViewClient() {
            private fun handleExternal(url: Uri): Boolean {
                if (url.scheme == "http" || url.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
                return false
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean = handleExternal(request.url)

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleExternal(Uri.parse(url))
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false)
                } else {
                    // Stash the callback and ask Android for permission; the
                    // web app already has a manual-location fallback if the
                    // user declines, so we simply deny here and let
                    // onRequestPermissionsResult() re-answer if they accept.
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        LOCATION_PERMISSION_REQUEST_CODE
                    )
                }
            }
        }

        webView.addJavascriptInterface(NativeBridge(this), "NativeBridge")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            val granted = hasLocationPermission()
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoOrigin = null
            pendingGeoCallback = null
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onPause() {
        webView.onPause()
        // Mirrors the JS compass's own battery hygiene: unregister the
        // sensor whenever the activity isn't in the foreground, regardless
        // of whether the Qibla screen is still the active WebView screen —
        // onResume() re-registers it if qiblaScreenVisible is still true.
        qiblaSensorController.stop()
        qiblaCompassView.stop()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // Widget may have been resized/added while the app was backgrounded;
        // cheap to just push the latest known state again.
        WidgetUpdater.refreshAll(this)
        if (qiblaScreenVisible) {
            qiblaCompassView.resetHeading()
            qiblaCompassView.start()
            qiblaSensorController.start()
            // The overlay's position may be stale (e.g. a rotation that
            // happened while backgrounded) — ask JS to re-measure and
            // re-send #qibla-compass-stack's rect rather than trusting the
            // last one we have.
            webView.evaluateJavascript("if (typeof window.__qiblaNativeRefreshRect === 'function') window.__qiblaNativeRefreshRect();", null)
        }
    }

    /** [NativeBridge.qiblaVisible] — called whenever the Qibla screen
     *  becomes visible/hidden in the WebView. Must run on the UI thread
     *  (NativeBridge dispatches it there via runOnUiThread). */
    fun setQiblaVisible(visible: Boolean, dark: Boolean) {
        qiblaScreenVisible = visible
        qiblaShowingWorking = false
        qiblaCompassView.setDarkMode(dark)
        if (visible) {
            val (lat, lon) = qiblaLatLon() ?: run {
                // Recipe not synced yet (e.g. onboarding not finished) —
                // nothing to bear toward; behave like "unavailable" rather
                // than showing a needle pointed at nothing.
                qiblaOverlay.visibility = android.view.View.GONE
                pushQiblaUnavailable(null)
                return
            }
            qiblaCompassView.setQiblaBearingDeg(Astro.qiblaBearing(lat, lon).toFloat())
            qiblaCompassView.resetHeading()
            // Deliberately NOT set to VISIBLE here. qiblaVisible() and
            // qiblaRingRect() are two separate, independently-dispatched
            // JS-interface calls — index.html's startCompassNative() calls
            // them back to back, but nothing guarantees this method and
            // setQiblaRingRect() land on the UI thread's message queue with
            // no draw pass in between. activity_main.xml gives #qibla_overlay
            // an explicit 0dp default specifically so it can never be
            // measured with the ambiguous "wrap_content parent + MATCH_PARENT
            // child" combination that used to let it expand toward
            // full-screen size and draw the ring/needle right on top of
            // everything below it — but a rect from a PREVIOUS Qibla-screen
            // visit could still be sitting in layoutParams from last time.
            // Resetting to a definite 0x0 here too, and staying GONE,
            // guarantees the overlay can only ever become visible together
            // with a freshly-measured rect for THIS visit — see
            // setQiblaRingRect() below, which is the only place that flips
            // it to VISIBLE.
            qiblaOverlay.layoutParams = FrameLayout.LayoutParams(0, 0)
            qiblaCompassView.start()
            qiblaSensorController.start()
        } else {
            qiblaSensorController.stop()
            qiblaCompassView.stop()
            qiblaOverlay.visibility = android.view.View.GONE
        }
    }

    /** [NativeBridge.qiblaRingRect] — positions/sizes [qiblaOverlay] to sit
     *  exactly on top of the WebView's `#qibla-compass-stack`, and is the
     *  ONLY place that reveals it (see the comment in [setQiblaVisible]
     *  above for why visibility and sizing are deliberately coupled here
     *  rather than being set independently). Values are CSS px (== dp,
     *  since this WebView is configured with initial-scale=1.0/
     *  width=device-width and no layout-affecting textZoom), so they're
     *  converted to real px via the display density the same way any
     *  other dp value in this app would be. */
    fun setQiblaRingRect(leftCssPx: Float, topCssPx: Float, widthCssPx: Float, heightCssPx: Float) {
        // A zero/negative measurement means JS measured
        // #qibla-compass-stack before it was actually laid out (or it's
        // simply not on screen right now) — never reveal the overlay off
        // of a degenerate rect like that.
        if (widthCssPx <= 0f || heightCssPx <= 0f) return
        val density = resources.displayMetrics.density
        val params = FrameLayout.LayoutParams(
            (widthCssPx * density).toInt(),
            (heightCssPx * density).toInt()
        )
        params.leftMargin = (leftCssPx * density).toInt()
        params.topMargin = (topCssPx * density).toInt()
        qiblaOverlay.layoutParams = params
        // Only reveal if the Qibla screen is still the one actually
        // showing — a rect can in principle arrive after the user already
        // navigated away (setQiblaVisible(false, ...) already ran), and
        // this must never re-show a now-stale overlay.
        if (qiblaScreenVisible) qiblaOverlay.visibility = android.view.View.VISIBLE
    }

    // ---- QiblaSensorController.Listener ----

    override fun onHeading(headingDeg: Float) {
        qiblaCompassView.setHeading(headingDeg)
        if (!qiblaShowingWorking && qiblaScreenVisible) {
            // Late-arriving reading after an unavailable timeout already
            // fired — same self-correcting behavior the JS fallback has
            // (it never tears down its listeners just because its own
            // 2.5s grace-period timer expired).
            qiblaShowingWorking = true
            qiblaOverlay.visibility = android.view.View.VISIBLE
            qiblaCompassView.start()
            pushQiblaWorking(calibrating = false)
        }
    }

    override fun onCalibrationStateChanged(calibrating: Boolean) {
        qiblaShowingWorking = true
        pushQiblaWorking(calibrating)
    }

    override fun onUnavailable() {
        qiblaOverlay.visibility = android.view.View.GONE
        qiblaCompassView.stop()
        val (lat, lon) = qiblaLatLon() ?: (null to null)
        pushQiblaUnavailable(if (lat != null && lon != null) Astro.qiblaBearing(lat, lon) else null)
    }

    /** index.html's __qiblaNativeWorking() — hint text is resolved/localized
     *  here (native string resources) rather than in JS, per the app's
     *  bilingual requirement for native-compass text. */
    private fun pushQiblaWorking(calibrating: Boolean) {
        val hint = qiblaString(if (calibrating) R.string.qibla_calib_hint else R.string.qibla_align_hint)
        webView.evaluateJavascript(
            "if (typeof window.__qiblaNativeWorking === 'function') window.__qiblaNativeWorking(${JSONObject.quote(hint)});",
            null
        )
    }

    /** index.html's __qiblaNativeUnavailable(). [bearing] is null when there's
     *  no location on record yet either — in that case the static-bearing
     *  text is skipped (nothing to compute it from) and only the generic
     *  "unavailable" note is shown. */
    private fun pushQiblaUnavailable(bearing: Double?) {
        val note = qiblaString(R.string.qibla_compass_unavailable)
        val staticBearing = if (bearing != null) {
            qiblaString(R.string.qibla_static_bearing, Math.round(bearing).toInt())
        } else ""
        webView.evaluateJavascript(
            "if (typeof window.__qiblaNativeUnavailable === 'function') " +
                "window.__qiblaNativeUnavailable(${JSONObject.quote(note)}, ${JSONObject.quote(staticBearing)});",
            null
        )
    }

    /** Reads lat/lon straight from the already-synced recipe prefs (same
     *  SharedPreferences AlarmScheduler writes via syncRecipeAndSchedule) —
     *  deliberately not a second location-fetch path. -999f mirrors
     *  AlarmScheduler's private LAT_UNSET sentinel for "no recipe synced
     *  yet". */
    private fun qiblaLatLon(): Pair<Double, Double>? {
        val p = AlarmScheduler.prefs(this)
        val lat = p.getFloat("recipe_lat", -999f)
        if (lat == -999f) return null
        val lon = p.getFloat("recipe_lon", 0f)
        return lat.toDouble() to lon.toDouble()
    }

    /** Resolves a string in the app's in-app-selected language (index.html's
     *  `lang` state, already synced into AlarmScheduler's prefs under the
     *  "lang" key by syncPrayerData) rather than the device's system
     *  locale — the two can differ, same as index.html's own I18N.lang(). */
    private fun qiblaString(resId: Int, vararg args: Any): String {
        val lang = AlarmScheduler.prefs(this).getString("lang", "en") ?: "en"
        val config = Configuration(resources.configuration)
        config.setLocale(Locale(lang))
        return createConfigurationContext(config).resources.getString(resId, *args)
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}
