package com.safwan.prayertime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface

/**
 * Native replacement for index.html's DeviceOrientationEvent-driven Qibla
 * compass (see the "Native path" section of the Qibla Compass JS for the
 * counterpart this replaces). Uses real hardware sensor fusion —
 * [Sensor.TYPE_ROTATION_VECTOR] — which is the same primitive a phone's
 * own built-in compass app is built on, falling back to
 * [Sensor.TYPE_ACCELEROMETER] + [Sensor.TYPE_MAGNETIC_FIELD] fused via
 * [SensorManager.getRotationMatrix] only if rotation-vector hardware isn't
 * present.
 *
 * Lifecycle discipline mirrors the JS version's startCompass()/stopCompass()
 * battery hygiene exactly: [start] registers listeners at
 * [SensorManager.SENSOR_DELAY_GAME] (fast enough for a live compass, still
 * far below what would meaningfully affect battery), [stop] unregisters
 * them — callers are expected to call [stop] whenever the Qibla screen is
 * not visible (navigated away, app backgrounded, activity paused), same as
 * the JS side's discipline.
 */
class QiblaSensorController(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        /** [headingDeg] is a true (not magnetic) compass heading, 0-360,
         *  already screen-rotation-corrected. */
        fun onHeading(headingDeg: Float)

        /** Fires once, the first time accuracy is known to be usable
         *  ([android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW] or
         *  better) OR degrades back down to
         *  [android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE]. */
        fun onCalibrationStateChanged(calibrating: Boolean)

        /** No usable sensor hardware, or no reading arrived within the
         *  startup grace period — mirrors the JS fallback timer. */
        fun onUnavailable()
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var rotationVectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var magneticFieldSensor: Sensor? = null

    private var registered = false
    private var gotFirstReading = false

    // Some devices' rotation-vector fusion reports a heading that's
    // consistently off (not just noisy — a fixed wrong angle) on the very
    // FIRST sensor registration after the app/process starts, and only a
    // full unregister+register cycle clears it — which is exactly what
    // leaving the app and reopening it does (onPause()/onResume() call
    // stop()/start() again), and why that "fixed" it. Deliberately NOT
    // reset in stop(), so this only ever runs once per app process — see
    // onRotationMatrixReady() below, which performs that same
    // unregister+register cycle itself, automatically and invisibly, the
    // first time a reading actually arrives, so the user never has to
    // discover the leave-and-reopen workaround themselves.
    private var warmupDone = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var unavailableTimeoutRunnable: Runnable? = null

    // Fallback-path raw readings, fused via getRotationMatrix same as the
    // rotation-vector path below.
    private val fallbackAccel = FloatArray(3)
    private val fallbackMag = FloatArray(3)
    private var haveAccel = false
    private var haveMag = false

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationResult = FloatArray(3)

    private var calibrating = true

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    onRotationMatrixReady()
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, fallbackAccel, 0, 3)
                    haveAccel = true
                    tryFallbackFusion()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, fallbackMag, 0, 3)
                    haveMag = true
                    tryFallbackFusion()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            // Rotation-vector accuracy reporting is far more reliable than
            // the JS version's approach of inferring "still calibrating" by
            // watching consecutive readings for agreement — the platform
            // already knows when the magnetometer feeding the fusion has
            // drifted.
            if (sensor.type != Sensor.TYPE_ROTATION_VECTOR && sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
            val nowCalibrating = accuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE
            if (nowCalibrating != calibrating) {
                calibrating = nowCalibrating
                listener.onCalibrationStateChanged(calibrating)
            }
        }
    }

    private fun tryFallbackFusion() {
        if (!haveAccel || !haveMag) return
        val ok = SensorManager.getRotationMatrix(rotationMatrix, null, fallbackAccel, fallbackMag)
        if (ok) onRotationMatrixReady()
    }

    /** Unregisters and immediately re-registers whichever sensor(s) are in
     *  use, keeping everything else (timeout, [gotFirstReading],
     *  [calibrating]) consistent with a fresh [start] — see [warmupDone]
     *  for why this exists. Runs once, silently, before the very first
     *  heading is ever handed to [listener], so no visibly-wrong reading
     *  reaches the screen. */
    private fun restartRegistration() {
        val sm = sensorManager ?: return
        sm.unregisterListener(sensorEventListener)
        registered = false
        gotFirstReading = false
        haveAccel = false
        haveMag = false
        val rv = rotationVectorSensor
        registered = if (rv != null) {
            sm.registerListener(sensorEventListener, rv, SensorManager.SENSOR_DELAY_GAME)
        } else {
            val okAccel = accelerometerSensor?.let {
                sm.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_GAME)
            } ?: false
            val okMag = magneticFieldSensor?.let {
                sm.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_GAME)
            } ?: false
            okAccel && okMag
        }
    }

    private fun onRotationMatrixReady() {
        if (!warmupDone) {
            warmupDone = true
            restartRegistration()
            return
        }

        val (remapAxisX, remapAxisY) = remapAxesForDisplayRotation()
        SensorManager.remapCoordinateSystem(rotationMatrix, remapAxisX, remapAxisY, remappedMatrix)
        SensorManager.getOrientation(remappedMatrix, orientationResult)
        var headingDeg = Math.toDegrees(orientationResult[0].toDouble()).toFloat()
        if (headingDeg < 0) headingDeg += 360f

        if (!gotFirstReading) {
            gotFirstReading = true
            cancelUnavailableTimeout()
            // Guarantees the listener hears a calibration state at least
            // once even if onAccuracyChanged's value never actually changes
            // from its initial `true` (e.g. a device that starts and stays
            // at low/unreliable accuracy the whole session) — otherwise the
            // hint text would never get shown at all.
            listener.onCalibrationStateChanged(calibrating)
        }
        listener.onHeading(headingDeg)
    }

    /** [SensorManager.remapCoordinateSystem] needs to know the display's
     *  current rotation so the heading stays correct regardless of how the
     *  phone is held — a portrait-only reading would drift 90°/180°/270°
     *  off in landscape otherwise. Display.getRotation() is deprecated on
     *  API 30+ in favor of Context#getDisplay(), but that replacement isn't
     *  available below API 30 — both are supported here across the
     *  minSdk 21 - targetSdk 34 range this app spans. */
    private fun remapAxesForDisplayRotation(): Pair<Int, Int> {
        val rotation = currentDisplayRotation()
        return when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y // ROTATION_0
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display: Display? = context.display
                display?.rotation ?: Surface.ROTATION_0
            } else {
                // Display.getRotation() via WindowManager.getDefaultDisplay()
                // is deprecated in favor of Context.getDisplay() — but that
                // replacement only exists on API 30+, and this app's minSdk
                // is 21, so this path is still required below API 30.
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
    }

    /** Registers sensor listeners. Safe to call again while already
     *  registered (no-op). Reports [Listener.onUnavailable] immediately if
     *  there's no usable hardware at all, or after a 2.5s grace period (same
     *  timeout the JS fallback used) if hardware exists but never produces a
     *  reading. */
    fun start() {
        if (registered) return
        val sm = sensorManager
        if (sm == null) {
            listener.onUnavailable()
            return
        }

        gotFirstReading = false
        calibrating = true
        haveAccel = false
        haveMag = false

        val rotationVector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationVectorSensor = rotationVector
        var ok: Boolean
        if (rotationVector != null) {
            ok = sm.registerListener(sensorEventListener, rotationVector, SensorManager.SENSOR_DELAY_GAME)
        } else {
            // No rotation-vector hardware — fall back to raw
            // accelerometer + magnetometer fusion, same maths
            // SensorManager.getRotationMatrix always used internally.
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            accelerometerSensor = accel
            magneticFieldSensor = mag
            if (accel == null || mag == null) {
                listener.onUnavailable()
                return
            }
            val okAccel = sm.registerListener(sensorEventListener, accel, SensorManager.SENSOR_DELAY_GAME)
            val okMag = sm.registerListener(sensorEventListener, mag, SensorManager.SENSOR_DELAY_GAME)
            ok = okAccel && okMag
        }

        if (!ok) {
            listener.onUnavailable()
            return
        }

        registered = true
        val timeout = Runnable { if (!gotFirstReading) listener.onUnavailable() }
        unavailableTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, UNAVAILABLE_TIMEOUT_MS)
    }

    /** Unregisters sensor listeners. Callers must invoke this whenever the
     *  Qibla screen stops being visible — sensors left registered keep
     *  firing (and draining battery) exactly like the bug this native
     *  implementation was written to avoid repeating from the JS side. */
    fun stop() {
        cancelUnavailableTimeout()
        if (registered) {
            sensorManager?.unregisterListener(sensorEventListener)
            registered = false
        }
        gotFirstReading = false
    }

    private fun cancelUnavailableTimeout() {
        unavailableTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        unavailableTimeoutRunnable = null
    }

    companion object {
        private const val UNAVAILABLE_TIMEOUT_MS = 2500L
    }
}
