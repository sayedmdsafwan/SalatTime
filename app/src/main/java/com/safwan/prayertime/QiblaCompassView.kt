package com.safwan.prayertime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.min

/**
 * Native replacement for index.html's `.compass-ring` / `.compass-dial` /
 * `.compass-needle` / `.compass-target` CSS+JS visuals — same look (a
 * circular paper-card ring with an inset paper border, N/E/S/W
 * labels on a rotating dial, a black-and-gold Kaaba needle on a two-tone
 * shaft, and a fixed triangular target marker above the ring), but painted
 * directly by a native Canvas `View` instead of DOM+CSS `transform`.
 *
 * Deliberately does NOT combine a `ValueAnimator` with manual per-frame
 * rotation on the same value — that exact combination (two independent
 * smoothing systems fighting each other) was the bug just fixed on the JS
 * side (see the "Fix stuttery/inaccurate Qibla compass" commit). There is
 * exactly ONE animation system here: [QiblaSensorController] writes a
 * target heading via [setHeading]; a single [Choreographer] frame callback
 * (Android's `requestAnimationFrame` equivalent) eases the painted angle
 * toward that target once per display frame and invalidates. Nothing else
 * touches rotation.
 */
class QiblaCompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- theming ----
    private var isDark = false

    // ---- rotation state (mirrors index.html's attachOrientation state) ----
    // Continuously increasing/decreasing (never wrapped to 0-360) so the
    // needle/dial always ease along the shortest visual path, same reason
    // as the JS version's needleRotation/targetRotation.
    private var needleRotation = 0f
    private var targetRotation = 0f
    private var qiblaBearingDeg = 0f
    private var smoothedHeading: Float? = null
    private var hasReading = false

    // Same constants as index.html's adaptiveSmoothing()/RENDER_EASE — kept
    // numerically identical so the native compass feels the same as the JS
    // one did, not just "similarly smooth".
    private val smoothingMin = 0.15f
    private val smoothingMax = 0.55f
    private val smoothingRampDeg = 25f
    private val renderEase = 0.35f

    private var lastFrameTimeNanos = 0L
    private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            Choreographer.getInstance().postFrameCallback(this)
            if (hasReading) {
                val dtMs = if (lastFrameTimeNanos == 0L) 16.7
                    else min((frameTimeNanos - lastFrameTimeNanos) / 1_000_000.0, 100.0)
                lastFrameTimeNanos = frameTimeNanos
                val frameFactor = (1 - Math.pow((1 - renderEase).toDouble(), dtMs / 16.7)).toFloat()
                needleRotation += shortestDelta(needleRotation, targetRotation) * frameFactor
                invalidate()
            } else {
                lastFrameTimeNanos = frameTimeNanos
            }
        }
    }

    /** Called from [QiblaSensorController.Listener.onHeading]. [headingDeg]
     *  is a true compass heading, 0-360. Applies the same adaptive
     *  low-pass filter as index.html's attachOrientation()/handle(). */
    fun setHeading(headingDeg: Float) {
        if (smoothedHeading == null) {
            smoothedHeading = headingDeg
            needleRotation = qiblaBearingDeg - headingDeg
            targetRotation = needleRotation
        } else {
            val prev = smoothedHeading!!
            val rawDelta = shortestDelta(prev, headingDeg)
            val factor = adaptiveSmoothing(rawDelta)
            val next = (prev + rawDelta * factor + 360f) % 360f
            smoothedHeading = next
            targetRotation += shortestDelta(targetRotation, qiblaBearingDeg - next)
        }
        hasReading = true
    }

    fun setQiblaBearingDeg(bearing: Float) {
        qiblaBearingDeg = bearing
    }

    fun setDarkMode(dark: Boolean) {
        if (isDark != dark) {
            isDark = dark
            refreshColors()
            invalidate()
        }
    }

    /** Resets smoothing/rotation state — call before a fresh [start] so a
     *  reopened Qibla screen doesn't ease in from a stale angle. */
    fun resetHeading() {
        smoothedHeading = null
        hasReading = false
        needleRotation = 0f
        targetRotation = 0f
    }

    fun start() {
        if (running) return
        running = true
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun shortestDelta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun adaptiveSmoothing(deltaDeg: Float): Float {
        val t = min(abs(deltaDeg) / smoothingRampDeg, 1f)
        return smoothingMin + (smoothingMax - smoothingMin) * t
    }

    // ---- drawing ----
    // Colors resolved once per theme change rather than on every onDraw,
    // since ContextCompat.getColor() is a resource-table lookup.
    private var cPaperCard = 0
    private var cPaper = 0
    private var cInk = 0
    private var cMuted = 0
    private var cHair = 0

    init {
        refreshColors()
    }

    private fun refreshColors() {
        cPaperCard = ContextCompat.getColor(context, if (isDark) R.color.qibla_paper_card_dark else R.color.qibla_paper_card_light)
        cPaper = ContextCompat.getColor(context, if (isDark) R.color.qibla_paper_dark else R.color.qibla_paper_light)
        cInk = ContextCompat.getColor(context, if (isDark) R.color.qibla_ink_dark else R.color.qibla_ink_light)
        cMuted = ContextCompat.getColor(context, if (isDark) R.color.qibla_muted_dark else R.color.qibla_muted_light)
        cHair = ContextCompat.getColor(context, if (isDark) R.color.qibla_hair_dark else R.color.qibla_hair_light)
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val insetRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dialLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val needleShaftMutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.qibla_needle_shaft_muted)
    }
    private val needleShaftAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.qibla_needle_shaft_accent)
    }
    private val needleKaabaBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.qibla_needle_kaaba_body)
    }
    private val needleKaabaBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.qibla_needle_kaaba_band)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Layout mirrors index.html: .compass-target (triangle, 11px tall +
        // 4px margin-bottom) sits above a 260x260 .compass-ring, both
        // centered horizontally — 11+4+260 = 275px total stack height. We
        // scale that same proportion to whatever size the WebView actually
        // measured (see setQiblaRingRect()).
        val targetH = h * (15f / 275f)
        val ringTop = targetH
        // SAFETY_SHRINK leaves deliberate breathing room below the ring:
        // without it, the ring filled the ENTIRE remaining height with
        // zero margin, so any tiny sub-pixel rounding between the WebView's
        // CSS-px measurement and the native px conversion was enough to
        // visibly overlap the hint text right below it — confirmed on a
        // real device. A few percent smaller than the "ideal" 260px design
        // size is a small, deliberate trade-off against ever touching
        // adjacent text again.
        val ringSize = min(w, h - ringTop) * SAFETY_SHRINK
        val ringLeft = (w - ringSize) / 2f
        val cx = ringLeft + ringSize / 2f
        val cy = ringTop + ringSize / 2f
        val radius = ringSize / 2f

        drawTargetMarker(canvas, cx, targetH)
        drawRing(canvas, cx, cy, radius)
        drawDial(canvas, cx, cy, radius)
        drawNeedle(canvas, cx, cy, radius)
    }

    private fun drawTargetMarker(canvas: Canvas, cx: Float, targetH: Float) {
        val triH = targetH * (11f / 15f)
        val triHalfW = triH * (7f / 11f)
        targetPaint.color = ContextCompat.getColor(context, R.color.brand)
        val path = Path().apply {
            moveTo(cx - triHalfW, triH)
            lineTo(cx + triHalfW, triH)
            lineTo(cx, 0f)
            close()
        }
        canvas.drawPath(path, targetPaint)
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        ringPaint.color = cPaperCard
        canvas.drawCircle(cx, cy, radius, ringPaint)

        ringBorderPaint.color = cHair
        ringBorderPaint.strokeWidth = 1f * resources.displayMetrics.density
        canvas.drawCircle(cx, cy, radius - ringBorderPaint.strokeWidth / 2f, ringBorderPaint)

        // Inset ring: CSS's `inset 0 0 0 10px var(--paper)`.
        val insetWidth = radius * (10f / 130f)
        insetRingPaint.color = cPaper
        insetRingPaint.strokeWidth = insetWidth
        canvas.drawCircle(cx, cy, radius - insetWidth / 2f, insetRingPaint)
    }

    private val dialLabels = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)

    private fun drawDial(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Dial rotates opposite to heading, same derivation as the JS
        // version's targetDialRotation, so N always points true north.
        val dialRotation = needleRotation - qiblaBearingDeg
        val labelRadius = radius * (0.86f)
        dialLabelPaint.textSize = radius * 0.16f
        canvas.save()
        canvas.rotate(dialRotation, cx, cy)
        for ((label, angleDeg) in dialLabels) {
            val rad = Math.toRadians((angleDeg - 90).toDouble())
            val lx = cx + (labelRadius * Math.cos(rad)).toFloat()
            val ly = cy + (labelRadius * Math.sin(rad)).toFloat()
            canvas.save()
            // Counter-rotate each label so the letters themselves stay
            // upright as the dial spins, matching the CSS version (its
            // N/E/S/W spans are positioned, not individually rotated).
            canvas.rotate(-dialRotation, lx, ly)
            dialLabelPaint.color = if (label == "N") cInk else cMuted
            dialLabelPaint.isFakeBoldText = label == "N"
            val fm = dialLabelPaint.fontMetrics
            canvas.drawText(label, lx, ly - (fm.ascent + fm.descent) / 2f, dialLabelPaint)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.save()
        canvas.rotate(needleRotation, cx, cy)

        // index.html's actual needle markup:
        //   <div class="compass-needle" ...> <!-- fills the 260px ring -->
        //     <svg viewBox="0 0 24 24" width="150" height="150" ...>
        // i.e. the SVG itself renders at a FIXED 150px inside the 260px
        // ring (not stretched to fill it), then centered by the parent
        // div's flex centering. Scaling to the full ring diameter (a
        // previous version of this code did `radius*2/24`) made the
        // needle ~1.73x too big and overflow past the ring into the hint
        // text below it — confirmed visually on-device. Scale
        // proportionally to whatever the ring's actual measured size is,
        // using the exact 150/260 ratio from the source markup, so this
        // stays correct at any screen density/size, not just the 260px
        // design reference.
        val svgSize = (radius * 2f) * (150f / 260f)
        val scale = svgSize / 24f
        canvas.translate(cx - svgSize / 2f, cy - svgSize / 2f)
        canvas.scale(scale, scale)

        // Muted lower shaft.
        canvas.drawRoundRect(RectF(10.8f, 13f, 13.2f, 21f), 1.2f, 1.2f, needleShaftMutedPaint)
        // Accent upper shaft.
        canvas.drawRect(RectF(10.8f, 8f, 13.2f, 13.3f), needleShaftAccentPaint)
        // Kaaba body.
        canvas.drawRoundRect(RectF(8.6f, 1.5f, 15.4f, 8f), 0.9f, 0.9f, needleKaabaBodyPaint)
        // Gold band.
        canvas.drawRect(RectF(8.6f, 3.6f, 15.4f, 5.1f), needleKaabaBandPaint)

        canvas.restore()
    }

    companion object {
        // How much smaller than the "ideal" available space the ring is
        // drawn — see the comment in onDraw() for why this exists. 0.92
        // leaves ~8% of the allocated height as margin, comfortably
        // covering measurement rounding between the WebView's CSS-px rect
        // and the native px conversion — verified to have room to spare,
        // not just barely enough.
        private const val SAFETY_SHRINK = 0.92f
    }
}
