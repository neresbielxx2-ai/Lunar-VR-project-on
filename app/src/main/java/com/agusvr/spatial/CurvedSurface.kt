package com.agusvr.spatial

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.agusvr.util.Logx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A genuinely CURVED display surface for arbitrary Android content
 * (module: AgusSpatialUI).
 *
 * The content view hierarchy is rendered into an offscreen bitmap and the
 * bitmap is presented by [stripCount] vertical slices arranged on a cylinder
 * segment (each slice rotated/translated in real 3D), so panels wrap around
 * the user like a curved cockpit display instead of sitting flat on the
 * screen. Touch events received by each slice are mapped back to content
 * coordinates and dispatched to the hidden content hierarchy, keeping every
 * button, scroll and WebView fully interactive.
 *
 * The content view must stay attached to an (invisible) host container so
 * engine-backed children (WebView) initialize correctly; the host is provided
 * by the window system.
 */
class CurvedSurface(
    context: Context,
    val content: View,
    private var contentW: Int,
    private var contentH: Int,
    private val stripCount: Int = 14,
    private val radiusFactor: Float = 1.55f
) : FrameLayout(context) {

    private var bitmap: Bitmap? = null
    private var surfaceCanvas: Canvas? = null
    private val strips = ArrayList<StripView>(stripCount)
    private var dirty = true
    private var grace = 0
    private val srcRect = Rect()
    private val dstRect = Rect()
    private var released = false

    init {
        clipChildren = false
        clipToPadding = false
        for (i in 0 until stripCount) {
            val s = StripView(i)
            strips.add(s)
            addView(s, LayoutParams(stripWidth(i), contentH).apply { leftMargin = stripLeft(i) })
            applyStripPose(s, i)
        }
        layoutContent()
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    private fun stripWidth(i: Int): Int {
        val base = contentW / stripCount
        return if (i == stripCount - 1) contentW - base * (stripCount - 1) else base
    }

    private fun stripLeft(i: Int): Int = (contentW / stripCount) * i

    /** Places one slice on the cylinder: yaw toward the user + depth offset. */
    private fun applyStripPose(s: StripView, i: Int) {
        val r = contentW * radiusFactor
        val centerX = stripLeft(i) + stripWidth(i) / 2f
        val phi = (centerX - contentW / 2f) / r          // azimuth in radians
        s.pivotX = stripWidth(i) / 2f
        s.pivotY = contentH / 2f
        s.rotationY = -Math.toDegrees(phi.toDouble()).toFloat()
        s.translationX = (sin(phi) * r - (centerX - contentW / 2f))
        s.translationZ = (r - cos(phi) * r) * 0.85f     // edges wrap toward the user
        s.cameraDistance = contentW * 2.4f
    }

    private fun layoutContent() {
        content.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY)
        )
        content.layout(0, 0, contentW, contentH)
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun ensureBitmap(): Bitmap? {
        if (contentW <= 0 || contentH <= 0) return null
        val b = bitmap
        if (b != null && b.width == contentW && b.height == contentH && !b.isRecycled) return b
        runCatching { b?.recycle() }
        return try {
            val nb = Bitmap.createBitmap(contentW, contentH, Bitmap.Config.ARGB_8888)
            bitmap = nb
            surfaceCanvas = Canvas(nb)
            nb
        } catch (t: Throwable) {
            Logx.e("CurvedSurface", "bitmap alloc failed ${contentW}x$contentH", t)
            null
        }
    }

    /** Queues a content repaint (next [tick]). */
    fun markDirty() {
        dirty = true
        if (grace < 24) grace = 24
    }

    /** Called every display frame by the window manager. */
    fun tick() {
        if (released) return
        if (dirty) {
            redrawNow()
        } else if (grace > 0) {
            grace--
            redrawNow()
        }
    }

    fun redrawNow() {
        val b = ensureBitmap() ?: return
        val c = surfaceCanvas ?: return
        b.eraseColor(Color.TRANSPARENT)
        try {
            content.draw(c)
        } catch (t: Throwable) {
            Logx.w("CurvedSurface", "content draw failed", t)
        }
        dirty = false
        for (s in strips) s.invalidate()
    }

    // ------------------------------------------------------------------
    // Lifecycle / resizing
    // ------------------------------------------------------------------

    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == contentW && h == contentH)) return
        contentW = w
        contentH = h
        for ((i, s) in strips.withIndex()) {
            val lp = s.layoutParams as LayoutParams
            lp.width = stripWidth(i)
            lp.height = contentH
            lp.leftMargin = stripLeft(i)
            s.layoutParams = lp
            applyStripPose(s, i)
        }
        layoutContent()
        ensureBitmap()
        markDirty()
    }

    fun release() {
        released = true
        runCatching { bitmap?.recycle() }
        bitmap = null
        surfaceCanvas = null
        removeAllViews()
    }

    // ------------------------------------------------------------------
    // Slices
    // ------------------------------------------------------------------

    private inner class StripView(val index: Int) : View(context) {
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val b = bitmap
            if (b == null || b.isRecycled || width <= 0 || height <= 0) return
            val hh = min(height, b.height)
            srcRect.set(stripLeft(index), 0, stripLeft(index) + width, hh)
            dstRect.set(0, 0, width, hh)
            canvas.drawBitmap(b, srcRect, dstRect, null)
            // depth cue: outer slices dim slightly, like a real curved glass
            val t = abs(index - (stripCount - 1) / 2f) / (stripCount / 2f)
            val a = (t * t * 52).toInt()
            if (a > 0) {
                shade.color = Color.argb(a, 3, 6, 12)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val mapped = MotionEvent.obtain(event)
            val cx = (stripLeft(index) + event.x).coerceIn(0f, contentW.toFloat())
            val cy = event.y.coerceIn(0f, contentH.toFloat())
            mapped.setLocation(cx, cy)
            val handled = try {
                content.dispatchTouchEvent(mapped)
            } catch (t: Throwable) {
                Logx.w("CurvedSurface", "touch dispatch failed", t)
                false
            }
            mapped.recycle()
            if (handled) markDirty()
            return handled || super.onTouchEvent(event)
        }
    }
}
