package com.agusvr.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.agusvr.util.Ui
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The custom animated AGUS logo (module: AgusSpatialUI identity).
 * Drawn entirely with Canvas: a breathing core "A", a hexagon frame,
 * a sweeping cyan orbit ring with a travelling spark and a violet counter-ring.
 * No stock Android spinners anywhere.
 */
class SplashLogoView(context: Context) : View(context) {

    private val cyan = Color.parseColor("#4DE8FF")
    private val violet = Color.parseColor("#9D6BFF")
    private val mint = Color.parseColor("#5EFFB1")

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val hexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#5488A8CC")
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2F7FF")
        textAlign = Paint.Align.CENTER
        typeface = Ui.display
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mint }
    private val hexPath = Path()
    private val rect = RectF()

    private var t = 0f
    private var intro = 0f
    private var anim: ValueAnimator? = null
    private var introAnim: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        glowPaint.shader = RadialGradient(
            w / 2f, h / 2f, min(w, h) * 0.55f,
            intArrayOf(Color.parseColor("#2A4DE8FF"), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
    }

    fun playIntro() {
        introAnim?.cancel()
        introAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { intro = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6000
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        introAnim?.cancel()
        introAnim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        val ang = t * (2f * Math.PI).toFloat()
        val breathe = 1f + 0.045f * sin(ang * 2f)

        // ambient glow
        glowPaint.alpha = (70 + 40 * intro).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        // hexagon frame (slow counter-rotation, drawn in on intro)
        val hexR = r * 0.78f * intro
        hexPaint.strokeWidth = r * 0.012f
        hexPath.reset()
        for (i in 0..6) {
            val a = -ang * 0.25f + i * (Math.PI / 3f).toFloat()
            val px = cx + hexR * cos(a)
            val py = cy + hexR * sin(a)
            if (i == 0) hexPath.moveTo(px, py) else hexPath.lineTo(px, py)
        }
        hexPath.close()
        canvas.drawPath(hexPath, hexPaint)

        // violet counter-ring (dashed feel via arcs)
        ringPaint.color = violet
        ringPaint.strokeWidth = r * 0.02f
        ringPaint.alpha = (190 * intro).toInt()
        rect.set(cx - r * 0.92f, cy - r * 0.92f, cx + r * 0.92f, cy + r * 0.92f)
        for (i in 0..2) {
            val start = (-ang * 40f) % 360f + i * 120f
            canvas.drawArc(rect, start, 46f, false, ringPaint)
        }

        // main cyan orbit ring with sweep gradient
        ringPaint.alpha = 255
        ringPaint.strokeWidth = r * 0.035f
        ringPaint.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, cyan, mint, Color.TRANSPARENT), null)
        rect.set(cx - r * 0.66f * breathe, cy - r * 0.66f * breathe, cx + r * 0.66f * breathe, cy + r * 0.66f * breathe)
        canvas.save()
        canvas.rotate(ang * 55f, cx, cy)
        canvas.drawArc(rect, 0f, 300f * intro, false, ringPaint)
        canvas.restore()
        ringPaint.shader = null

        // travelling spark on the ring
        val sa = ang * 2.2f
        val sx = cx + r * 0.66f * breathe * cos(sa)
        val sy = cy + r * 0.66f * breathe * sin(sa)
        sparkPaint.alpha = (255 * intro).toInt()
        canvas.drawCircle(sx, sy, r * 0.035f, sparkPaint)
        sparkPaint.alpha = (90 * intro).toInt()
        canvas.drawCircle(sx, sy, r * 0.075f, sparkPaint)
        sparkPaint.alpha = 255

        // core letter
        corePaint.textSize = r * 0.72f * intro
        corePaint.alpha = (255 * intro).toInt()
        canvas.drawText("A", cx, cy - (corePaint.ascent() + corePaint.descent()) / 2f, corePaint)

        // orbiting micro-dots
        for (i in 0..2) {
            val a2 = -ang * 1.4f + i * (2f * Math.PI.toFloat() / 3f)
            val rr = r * (0.86f + 0.03f * sin(ang * 3f + i))
            val dx = cx + rr * cos(a2)
            val dy = cy + rr * sin(a2)
            ringPaint.color = cyan
            ringPaint.alpha = (150 * intro).toInt()
            ringPaint.style = Paint.Style.FILL
            canvas.drawCircle(dx, dy, r * 0.018f, ringPaint)
            ringPaint.style = Paint.Style.STROKE
        }
    }
}
