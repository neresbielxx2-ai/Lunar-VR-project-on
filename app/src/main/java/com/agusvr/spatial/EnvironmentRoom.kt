package com.agusvr.spatial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.sin

/**
 * The immersive VR room (module: AgusSpatialUI): a cylindrical panorama of
 * deep-space panels wrapping around the user, a receding floor grid and a
 * soft ceiling glow — real 3D-placed views, not a flat wallpaper.
 *
 * In rear-camera passthrough mode the panorama hides and only the floor /
 * glow remain (semi-transparent), so the real world stays visible underneath
 * the floating UI.
 */
class EnvironmentRoom(context: Context) : FrameLayout(context) {

    private val band = FrameLayout(context)
    private val floor = FloorView(context)
    private val ceiling = CeilingView(context)
    private val pedestal = PedestalView(context)
    private var w = 0
    private var h = 0
    var passthrough = false
        private set

    init {
        clipChildren = false
        clipToPadding = false
        band.clipChildren = false
        addView(band, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ceiling, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(floor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(pedestal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setPassthrough(on: Boolean) {
        passthrough = on
        band.animate().alpha(if (on) 0f else 1f).setDuration(420).start()
        ceiling.animate().alpha(if (on) 0.35f else 1f).setDuration(420).start()
        floor.animate().alpha(if (on) 0.55f else 1f).setDuration(420).start()
        pedestal.animate().alpha(if (on) 0.5f else 1f).setDuration(420).start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        this.w = w
        this.h = h
        layoutRoom()
    }

    private fun layoutRoom() {
        if (w <= 0 || h <= 0) return
        // ---- panorama cylinder -------------------------------------
        band.removeAllViews()
        val n = 18
        val r = w * 0.98f
        val stripW = (w / n.toFloat() * 1.75f).toInt()
        val stripH = (h * 1.65f).toInt()
        for (i in 0 until n) {
            val v = PanoramaStripView(context, i, n)
            val theta = Math.toRadians(((i - (n - 1) / 2f) * 10.5f).toDouble())
            val lp = LayoutParams(stripW, stripH).apply {
                leftMargin = w / 2 - stripW / 2
                topMargin = h / 2 - stripH / 2
            }
            band.addView(v, lp)
            v.pivotX = stripW / 2f
            v.pivotY = stripH / 2f
            v.rotationY = -Math.toDegrees(theta).toFloat() * 0.96f
            v.translationX = (sin(theta) * r * 1.02f).toFloat()
            v.translationZ = ((cos(theta) - 1f) * r * 0.85f).toFloat()
            v.cameraDistance = w * 2.2f
        }
        band.cameraDistance = w * 2.4f
        // ---- floor / ceiling / pedestal ----------------------------
        floor.setup(w, h)
        ceiling.setup(w, h)
        pedestal.setup(w, h)
    }

    /** Head-look parallax: the room rotates gently against the gyro. */
    fun parallax(yawDeg: Float, pitchDeg: Float) {
        if (w <= 0) return
        band.rotationY = -yawDeg * 0.42f
        band.rotationX = pitchDeg * 0.30f
        band.translationX = -yawDeg * w * 0.0011f
        band.translationY = -pitchDeg * h * 0.0011f
        floor.translationX = -yawDeg * w * 0.0022f
        floor.rotation = yawDeg * 0.06f
        ceiling.translationX = -yawDeg * w * 0.0016f
        pedestal.translationX = -yawDeg * w * 0.0018f
        pedestal.translationY = -pitchDeg * h * 0.0012f
    }

    // ------------------------------------------------------------------
    // Painted pieces
    // ------------------------------------------------------------------

    /** One vertical slice of the space panorama (stars + nebula + horizon). */
    private class PanoramaStripView(ctx: Context, private val index: Int, private val count: Int) : View(ctx) {
        private val bg = Paint()
        private val star = Paint(Paint.ANTI_ALIAS_FLAG)
        private val nebula = Paint(Paint.ANTI_ALIAS_FLAG)
        private val horizon = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rnd = java.util.Random(index * 7919L + 13L)
        private val stars = Array(46) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.62f, 0.6f + rnd.nextFloat() * 1.5f, 0.25f + rnd.nextFloat() * 0.75f) }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            if (bg.shader == null) {
                bg.shader = LinearGradient(0f, 0f, 0f, h,
                    intArrayOf(Color.parseColor("#04060C"), Color.parseColor("#081120"),
                        Color.parseColor("#0A1626"), Color.parseColor("#050A12")),
                    floatArrayOf(0f, 0.42f, 0.66f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, w, h, bg)
            // nebula tint, hue shifts around the cylinder
            val t = index / count.toFloat()
            val tint = Color.argb(26, (24 + 40 * t).toInt(), (40 + 26 * (1 - t)).toInt(), (86 + 60 * t).toInt())
            nebula.shader = RadialGradient(w * 0.5f, h * 0.52f, w * 0.9f, tint, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, nebula)
            // stars
            for (s in stars) {
                star.color = Color.argb((s[3] * 200).toInt(), 214, 230, 255)
                canvas.drawCircle(s[0] * w, s[1] * h, s[2], star)
            }
            // horizon glow line
            horizon.color = Color.argb(58, 74, 152, 220)
            canvas.drawRect(0f, h * 0.655f, w, h * 0.662f, horizon)
            horizon.color = Color.argb(22, 74, 152, 220)
            canvas.drawRect(0f, h * 0.662f, w, h * 0.685f, horizon)
        }
    }

    /** Receding floor: perspective grid + reflection pool under the UI. */
    private class FloorView(ctx: Context) : View(ctx) {
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fade = Paint()
        private val pool = Paint(Paint.ANTI_ALIAS_FLAG)
        private var w = 0f
        private var h = 0f

        fun setup(w: Int, h: Int) {
            this.w = w.toFloat()
            this.h = h.toFloat()
            pivotX = this.w / 2f
            pivotY = this.h * 0.54f
            rotationX = 55f
            translationY = this.h * 0.10f
            cameraDistance = this.w * 2.0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (w <= 0 || h <= 0) return
            if (fade.shader == null) {
                fade.shader = LinearGradient(0f, h * 0.5f, 0f, h,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#0A1424"), Color.parseColor("#060B14")),
                    floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, h * 0.5f, w, h, fade)
            // converging verticals
            val vpx = w / 2f
            val vpy = h * 0.52f
            grid.strokeWidth = 1.4f
            for (i in -9..9) {
                val x = vpx + i * w * 0.11f
                grid.color = Color.argb(if (i == 0) 26 else 40 - kotlin.math.abs(i) * 3, 86, 140, 200)
                canvas.drawLine(vpx + (x - vpx) * 0.06f, vpy, x, h, grid)
            }
            // horizontals with perspective spacing
            var y = vpy
            var step = h * 0.012f
            var alpha = 64
            while (y < h && step > 0.4f) {
                y += step
                step *= 1.38f
                alpha = (alpha * 0.93f).toInt()
                grid.color = Color.argb(alpha, 86, 140, 200)
                canvas.drawLine(0f, y, w, y, grid)
            }
            // reflection pool glow
            pool.shader = RadialGradient(vpx, h * 0.78f, w * 0.42f,
                Color.argb(46, 56, 118, 190), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h * 0.5f, w, h, pool)
        }
    }

    /** Soft overhead glow — sells the "inside a room" feeling. */
    private class CeilingView(ctx: Context) : View(ctx) {
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private var w = 0f
        private var h = 0f

        fun setup(w: Int, h: Int) {
            this.w = w.toFloat()
            this.h = h.toFloat()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (w <= 0 || h <= 0) return
            glow.shader = RadialGradient(w / 2f, -h * 0.12f, w * 0.75f,
                Color.argb(40, 64, 118, 190), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h * 0.5f, glow)
        }
    }

    /** Elliptical light pedestal behind the home dock. */
    private class PedestalView(ctx: Context) : View(ctx) {
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private var w = 0f
        private var h = 0f

        fun setup(w: Int, h: Int) {
            this.w = w.toFloat()
            this.h = h.toFloat()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (w <= 0 || h <= 0) return
            glow.shader = RadialGradient(w / 2f, h * 0.60f, w * 0.34f,
                Color.argb(34, 70, 130, 210), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.save()
            canvas.scale(1f, 0.42f, w / 2f, h * 0.60f)
            canvas.drawRect(0f, 0f, w, h * 1.6f, glow)
            canvas.restore()
        }
    }
}
