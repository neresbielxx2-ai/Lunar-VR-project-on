package com.agusvr.spatial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import com.agusvr.hand.Gesture
import com.agusvr.hand.HandFrame
import com.agusvr.hand.HandSkeleton
import com.agusvr.hand.Handedness
import com.agusvr.hand.HandSnapshot
import com.agusvr.performance.QualityProfile
import com.agusvr.point.PointInteraction
import com.agusvr.settings.SettingsRepo
import com.agusvr.util.Ui
import com.agusvr.util.colorWithAlpha
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the hand tracking visuals on top of the whole VR world
 * (modules: AgusHandTracking visuals + AgusPointInteraction visuals):
 *
 *  - skeletal hands (21 landmarks + connections) with left/right colors;
 *  - fingertip reticle: the small circle at the index tip;
 *  - the pointing RAY with a circle at its end (☝ ───── ○);
 *  - hover highlight on the targeted element + dwell progress ring
 *    (proximity selection feedback);
 *  - the central AGUS hologram on the home screen;
 *  - optional debug telemetry.
 *
 * All paint/path objects are pre-allocated — nothing is created in onDraw.
 */
class SpatialOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var frame: HandFrame? = null
    private var point: PointInteraction? = null
    private var profile: QualityProfile = QualityProfile.current(0)
    private var showHologram = true
    private var startNs = System.nanoTime()

    // --- preallocated paint ---
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tipGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val endCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val hoverFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dwellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT
    }
    private val holoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val holoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = Color.parseColor("#E6F2F7FF")
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC4DE8FF"); textSize = 26f
    }
    private val rectF = RectF()
    private val path = Path()

    private val cyan = Color.parseColor("#4DE8FF")
    private val violet = Color.parseColor("#9D6BFF")
    private val mint = Color.parseColor("#5EFFB1")
    private val white = Color.parseColor("#F2F7FF")

    fun setProfile(p: QualityProfile) { profile = p }

    fun setHologramVisible(visible: Boolean) {
        if (showHologram != visible) { showHologram = visible; invalidate() }
    }

    /** Called by the VR frame loop (~display rate). */
    fun tick(frame: HandFrame?, point: PointInteraction?) {
        this.frame = frame
        this.point = point
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = (System.nanoTime() - startNs) / 1_000_000_000f
        if (showHologram) drawHologram(canvas, t)
        val f = frame
        if (f != null && SettingsRepo.handEnabled) {
            for (hand in f.hands) drawHand(canvas, hand, t)
        }
        drawPointLayer(canvas, t)
        if (SettingsRepo.debugOverlay) drawDebug(canvas, f)
    }

    // ------------------------------------------------------------------
    private fun drawHand(canvas: Canvas, hand: HandSnapshot, t: Float) {
        val pts = hand.landmarksScreen
        if (pts.size < 21) return
        val base = if (hand.handedness == Handedness.LEFT) cyan else violet
        val detailed = profile.overlayDetail

        // bones
        if (detailed) {
            bonePaint.color = colorWithAlpha(base, 0.85f)
            bonePaint.strokeWidth = dpF(2.2f)
            for (c in HandSkeleton.CONNECTIONS) {
                val a = pts[c[0]]; val b = pts[c[1]]
                canvas.drawLine(a.x, a.y, b.x, b.y, bonePaint)
            }
            // joints
            jointPaint.color = colorWithAlpha(white, 0.9f)
            val r = dpF(2.6f)
            for (p in pts) canvas.drawCircle(p.x, p.y, r, jointPaint)
            // palm glow
            if (profile.glowEnabled) {
                val wrist = pts[0]; val mid = pts[9]
                val cx = (wrist.x + mid.x) / 2; val cy = (wrist.y + mid.y) / 2
                val rad = hypot(mid.x - wrist.x, mid.y - wrist.y) * 1.6f + dpF(10f)
                tipGlowPaint.shader = RadialGradient(
                    cx, cy, rad.coerceAtLeast(1f),
                    intArrayOf(colorWithAlpha(base, 0.22f), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, rad, tipGlowPaint)
                tipGlowPaint.shader = null
            }
        } else {
            // cheap mode: palm dot + fingertip only
            jointPaint.color = colorWithAlpha(base, 0.8f)
            canvas.drawCircle(pts[9].x, pts[9].y, dpF(5f), jointPaint)
        }

        // gesture label near wrist (small, unobtrusive)
        if (detailed && hand.gesture != Gesture.NONE) {
            debugPaint.color = colorWithAlpha(base, 0.9f)
            debugPaint.textSize = dpF(9.5f)
            debugPaint.typeface = Ui.mono
            canvas.drawText(hand.gesture.label, pts[0].x + dpF(8f), pts[0].y + dpF(14f), debugPaint)
        }
    }

    // ------------------------------------------------------------------
    private fun drawPointLayer(canvas: Canvas, t: Float) {
        val p = point ?: return
        val hand = p.activeHand ?: return
        val base = if (hand.handedness == Handedness.LEFT) cyan else violet
        val tip = hand.fingertipScreen

        // 1) fingertip reticle — the small circle at the finger tip ☝ → ○
        val pulse = 1f + 0.12f * sin(t * 6f)
        if (profile.glowEnabled) {
            tipGlowPaint.shader = RadialGradient(
                tip.x, tip.y, dpF(26f) * pulse,
                intArrayOf(colorWithAlpha(base, 0.35f), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(tip.x, tip.y, dpF(26f) * pulse, tipGlowPaint)
            tipGlowPaint.shader = null
        }
        reticlePaint.color = white
        reticlePaint.strokeWidth = dpF(2f)
        canvas.drawCircle(tip.x, tip.y, dpF(6.5f) * pulse, reticlePaint)
        jointPaint.color = colorWithAlpha(base, 0.95f)
        canvas.drawCircle(tip.x, tip.y, dpF(2.4f), jointPaint)

        // 2) the ray ─────
        val rayEnd = p.rayEnd ?: return
        val isPointing = hand.gesture == Gesture.POINT
        if (isPointing || p.hoverView != null) {
            val intensity = SettingsRepo.rayIntensity
            rayPaint.strokeWidth = dpF(2.4f) * (0.6f + intensity * 0.6f)
            rayPaint.shader = LinearGradient(
                tip.x, tip.y, rayEnd.x, rayEnd.y,
                intArrayOf(colorWithAlpha(base, 0.95f * intensity), colorWithAlpha(base, 0.05f)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawLine(tip.x, tip.y, rayEnd.x, rayEnd.y, rayPaint)
            rayPaint.shader = null

            // ray end circle ○
            val endPt = p.interactionPoint ?: rayEnd
            val hit = p.hoverView != null
            endCirclePaint.color = if (hit) mint else colorWithAlpha(white, 0.85f)
            endCirclePaint.strokeWidth = dpF(if (hit) 2.6f else 1.8f)
            canvas.drawCircle(endPt.x, endPt.y, dpF(if (hit) 9f else 7f), endCirclePaint)
            if (hit && profile.glowEnabled) {
                tipGlowPaint.shader = RadialGradient(
                    endPt.x, endPt.y, dpF(22f),
                    intArrayOf(colorWithAlpha(mint, 0.4f), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawCircle(endPt.x, endPt.y, dpF(22f), tipGlowPaint)
                tipGlowPaint.shader = null
            }
        }

        // 3) hover highlight on the targeted element
        val rect: Rect? = p.hoverRect
        if (rect != null && p.hoverView != null && rect.width() > 4 && rect.height() > 4) {
            rectF.set(rect)
            val grow = dpF(5f)
            rectF.inset(-grow, -grow)
            hoverPaint.color = colorWithAlpha(mint, 0.9f)
            hoverPaint.strokeWidth = dpF(1.6f)
            canvas.drawRoundRect(rectF, dpF(14f), dpF(14f), hoverPaint)
            hoverFillPaint.color = colorWithAlpha(mint, 0.06f + 0.03f * sin(t * 5f))
            canvas.drawRoundRect(rectF, dpF(14f), dpF(14f), hoverFillPaint)
            // corner brackets
            hoverPaint.strokeWidth = dpF(2.4f)
            val c = dpF(16f)
            drawCorner(canvas, rectF.left, rectF.top, c, 1, 1)
            drawCorner(canvas, rectF.right, rectF.top, c, -1, 1)
            drawCorner(canvas, rectF.left, rectF.bottom, c, 1, -1)
            drawCorner(canvas, rectF.right, rectF.bottom, c, -1, -1)
        }

        // 4) dwell progress ring around the reticle (proximity confirmation)
        val dwell = p.dwellProgress
        if (dwell > 0.01f && dwell < 1.001f) {
            val r = dpF(16f)
            rectF.set(tip.x - r, tip.y - r, tip.x + r, tip.y + r)
            dwellPaint.color = colorWithAlpha(mint, 0.95f)
            dwellPaint.strokeWidth = dpF(3.4f)
            dwellPaint.pathEffect = null
            canvas.drawArc(rectF, -90f, 360f * dwell, false, dwellPaint)
            dwellPaint.color = colorWithAlpha(white, 0.25f)
            dwellPaint.strokeWidth = dpF(1.4f)
            canvas.drawArc(rectF, -90f + 360f * dwell, 360f * (1f - dwell), false, dwellPaint)
        }

        // 5) grab state indicator
        if (p.grabActive) {
            val r = dpF(12f + 2.5f * sin(t * 10f))
            reticlePaint.color = colorWithAlpha(mint, 0.9f)
            reticlePaint.strokeWidth = dpF(2f)
            reticlePaint.pathEffect = DashPathEffect(floatArrayOf(dpF(5f), dpF(5f)), t * 30f)
            canvas.drawCircle(tip.x, tip.y, r, reticlePaint)
            reticlePaint.pathEffect = null
        }
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, len: Float, sx: Int, sy: Int) {
        canvas.drawLine(x, y, x + len * sx, y, hoverPaint)
        canvas.drawLine(x, y, x, y + len * sy, hoverPaint)
    }

    // ------------------------------------------------------------------
    private fun drawHologram(canvas: Canvas, t: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h * 0.47f
        val unit = min(w, h)
        val r = unit * 0.105f
        if (!profile.glowEnabled && !profile.particlesEnabled) {
            // still draw a minimal hologram in performance mode
        }
        // soft core glow
        if (profile.glowEnabled) {
            tipGlowPaint.shader = RadialGradient(
                cx, cy, r * 2.4f,
                intArrayOf(Color.parseColor("#2E4DE8FF"), Color.parseColor("#149D6BFF"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r * 2.4f, tipGlowPaint)
            tipGlowPaint.shader = null
        }
        // rotating rings
        holoPaint.strokeWidth = dpF(1.4f)
        for (i in 0..2) {
            val rr = r * (0.7f + i * 0.32f)
            val spin = t * (0.5f + i * 0.28f) * (if (i % 2 == 0) 1f else -1f)
            val sweep = 120f + i * 60f
            holoPaint.color = colorWithAlpha(if (i % 2 == 0) cyan else violet, 0.55f - i * 0.1f)
            rectF.set(cx - rr, cy - rr * (0.42f + 0.2f * cos(spin * 0.7f)), cx + rr, cy + rr * (0.42f + 0.2f * cos(spin * 0.7f)))
            canvas.drawArc(rectF, (spin * 57.3f) % 360f, sweep, false, holoPaint)
        }
        // hex core
        path.reset()
        for (i in 0..5) {
            val a = Math.toRadians((60.0 * i - 90.0) + t * 12.0)
            val px = cx + (r * 0.42f * cos(a)).toFloat()
            val py = cy + (r * 0.42f * sin(a)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        holoPaint.color = colorWithAlpha(white, 0.8f)
        holoPaint.strokeWidth = dpF(1.6f)
        canvas.drawPath(path, holoPaint)
        // wordmark
        holoTextPaint.typeface = Ui.display
        holoTextPaint.textSize = dpF(13f)
        holoTextPaint.letterSpacing = 0.5f
        holoTextPaint.color = colorWithAlpha(white, 0.75f + 0.15f * sin(t * 2f))
        canvas.drawText("AGUS VR", cx, cy + r * 1.9f, holoTextPaint)
        holoTextPaint.letterSpacing = 0.3f
        holoTextPaint.textSize = dpF(7.5f)
        holoTextPaint.typeface = Ui.mono
        holoTextPaint.color = colorWithAlpha(cyan, 0.5f)
        canvas.drawText("SISTEMA ESPACIAL", cx, cy + r * 2.35f, holoTextPaint)
        holoTextPaint.letterSpacing = 0f
    }

    // ------------------------------------------------------------------
    private fun drawDebug(canvas: Canvas, f: HandFrame?) {
        debugPaint.typeface = Ui.mono
        debugPaint.textSize = dpF(10f)
        debugPaint.color = colorWithAlpha(cyan, 0.85f)
        var y = dpF(64f)
        canvas.drawText("hands=${f?.hands?.size ?: 0}", dpF(10f), y, debugPaint); y += dpF(13f)
        f?.hands?.forEach { h ->
            canvas.drawText(
                "${h.handedness} ${h.gesture} prox=${"%.2f".format(h.proximity)} conf=${"%.2f".format(h.confidence)}",
                dpF(10f), y, debugPaint
            )
            y += dpF(13f)
        }
        val p = point
        canvas.drawText("dwell=${"%.2f".format(p?.dwellProgress ?: 0f)} hover=${p?.hoverView?.javaClass?.simpleName ?: "-"}", dpF(10f), y, debugPaint)
    }

    private fun dpF(v: Float) = v * resources.displayMetrics.density
}
