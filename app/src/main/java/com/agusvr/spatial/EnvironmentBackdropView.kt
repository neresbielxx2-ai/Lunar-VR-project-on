package com.agusvr.spatial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The virtual "Ambiente VR" used when the rear camera is unavailable or the
 * user declined the permission (module: AgusSpatialUI). A calm deep-space
 * environment: nebula washes, a starfield, and an animated perspective grid
 * horizon — clearly Agus identity, not a copy of any existing headset UI.
 */
class EnvironmentBackdropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Star { var x = 0f; var y = 0f; var r = 0f; var ph = 0f; var sp = 0f }

    private val stars = Array(160) { Star() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }
    private val path = Path()
    private var bgShader: Shader? = null
    private var nebula1: Shader? = null
    private var nebula2: Shader? = null
    private var lastW = 0
    private var lastH = 0
    private val startNs = System.nanoTime()
    private var lastFrameNs = System.nanoTime()

    init {
        setWillNotDraw(false)
        val rnd = Random(11)
        for (s in stars) {
            s.x = rnd.nextFloat()
            s.y = rnd.nextFloat() * 0.75f
            s.r = 0.4f + rnd.nextFloat() * 1.6f
            s.ph = rnd.nextFloat() * 6.28f
            s.sp = 0.4f + rnd.nextFloat() * 1.6f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == lastW && h == lastH) return
        lastW = w; lastH = h
        bgShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#05070F"), Color.parseColor("#0A1024"), Color.parseColor("#05070F")),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        nebula1 = RadialGradient(
            w * 0.25f, h * 0.3f, w * 0.5f,
            intArrayOf(Color.parseColor("#223E2A8F"), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        nebula2 = RadialGradient(
            w * 0.78f, h * 0.22f, w * 0.42f,
            intArrayOf(Color.parseColor("#1F1D6E8F"), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
        lastFrameNs = now
        val t = (now - startNs) / 1e9f
        val d = resources.displayMetrics.density

        bgShader?.let { paint.shader = it; canvas.drawRect(0f, 0f, w, h, paint) }
        nebula1?.let {
            paint.shader = it
            canvas.save()
            canvas.translate(sin(t * 0.05f) * 30f * d, cos(t * 0.04f) * 18f * d)
            canvas.drawRect(0f, 0f, w, h, paint)
            canvas.restore()
        }
        nebula2?.let {
            paint.shader = it
            canvas.save()
            canvas.translate(cos(t * 0.045f) * 24f * d, sin(t * 0.06f) * 16f * d)
            canvas.drawRect(0f, 0f, w, h, paint)
            canvas.restore()
        }
        paint.shader = null

        // stars
        for (s in stars) {
            val a = 0.25f + 0.75f * (0.5f + 0.5f * sin(t * s.sp + s.ph))
            paint.color = Color.argb((a * 200).toInt(), 0xDD, 0xEA, 0xFF)
            canvas.drawCircle(s.x * w, s.y * h, s.r * d, paint)
        }

        // perspective grid horizon (bottom third)
        val horizonY = h * 0.66f
        val vx = w / 2f + sin(t * 0.1f) * w * 0.02f
        gridPaint.color = Color.parseColor("#334DE8FF")
        gridPaint.strokeWidth = 1f * d
        // radial lines
        for (i in -10..10) {
            val xEnd = vx + i * w * 0.14f
            canvas.drawLine(vx, horizonY, xEnd, h + 40f, gridPaint)
        }
        // moving horizontal lines
        val rows = 9
        for (i in 0 until rows) {
            val phase = ((t * 0.16f + i / rows.toFloat()) % 1f)
            val y = horizonY + (h - horizonY) * (phase * phase * 1.15f)
            val alpha = (0.06f + 0.30f * phase)
            gridPaint.color = Color.argb((alpha * 255).toInt(), 0x4D, 0xE8, 0xFF)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        // horizon glow line
        paint.shader = LinearGradient(
            0f, horizonY, w, horizonY,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#594DE8FF"), Color.parseColor("#599D6BFF"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * d
        canvas.drawLine(0f, horizonY, w, horizonY, paint)
        paint.style = Paint.Style.FILL
        paint.shader = null
    }
}
