package com.agusvr.spatial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.agusvr.performance.QualityProfile
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ambient atmosphere drawn above the camera feed (module: AgusSpatialUI):
 * drifting light particles, a subtle bottom horizon glow and a soft vignette
 * that ties the real environment and the floating UI together. Fully disabled
 * in Performance mode / battery saver.
 */
class AtmosphereView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Particle {
        var x = 0f; var y = 0f; var z = 0f; var speed = 0f; var phase = 0f; var size = 0f
    }

    private val particles = Array(140) { Particle() }
    private var particleCount = 0
    private var profile: QualityProfile = QualityProfile.current(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var vignette: Shader? = null
    private var horizon: Shader? = null
    private var lastW = 0
    private var lastH = 0
    private var startNs = System.nanoTime()
    private var lastFrameNs = System.nanoTime()

    init {
        setWillNotDraw(false)
        val rnd = Random(47)
        for (p in particles) {
            p.x = rnd.nextFloat()
            p.y = rnd.nextFloat()
            p.z = 0.25f + rnd.nextFloat() * 0.75f
            p.speed = 0.006f + rnd.nextFloat() * 0.02f
            p.phase = rnd.nextFloat() * 6.28f
            p.size = 1f + rnd.nextFloat() * 2.4f
        }
    }

    fun setProfile(p: QualityProfile) {
        profile = p
        particleCount = if (p.particlesEnabled) p.particleCount.coerceAtMost(particles.size) else 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != lastW || h != lastH) {
            lastW = w; lastH = h
            vignette = RadialGradient(
                w / 2f, h / 2f, (maxOf(w, h) * 0.78f),
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.parseColor("#59020308")),
                floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP
            )
            horizon = RadialGradient(
                w / 2f, h * 1.05f, h * 0.55f,
                intArrayOf(Color.parseColor("#1F4DE8FF"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1_000_000_000f).coerceAtMost(0.1f)
        lastFrameNs = now
        val t = (now - startNs) / 1_000_000_000f

        horizon?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        if (particleCount > 0) {
            for (i in 0 until particleCount) {
                val p = particles[i]
                p.y -= p.speed * dt * (0.4f + p.z)
                if (p.y < -0.05f) { p.y = 1.05f; p.x = kotlin.random.Random.nextFloat() }
                val px = p.x * w + sin(t * 0.6f + p.phase) * 12f * p.z
                val py = p.y * h
                val alpha = (0.10f + 0.22f * p.z) * (0.75f + 0.25f * sin(t * 1.7f + p.phase))
                paint.color = Color.argb((alpha * 255).toInt().coerceIn(0, 255), 0xB8, 0xE8, 0xFF)
                canvas.drawCircle(px, py, p.size * p.z * resources.displayMetrics.density, paint)
            }
        }

        vignette?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }
    }
}
