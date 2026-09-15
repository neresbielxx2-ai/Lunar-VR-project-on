package com.agusvr.vr3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.agusvr.util.Logx
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Render-to-texture bridge for one floating 3D panel (module: AgusSpatialUI/3D).
 *
 * The panel's Android view hierarchy lives attached (INVISIBLE) in a host
 * container so engine-backed children initialize; every repaint draws it into
 * an ARGB bitmap whose pixels are uploaded to a Filament texture. Touches that
 * the renderer picks against the panel mesh arrive here as content-space
 * coordinates and are dispatched to the hidden hierarchy, keeping the 3D menu
 * fully interactive.
 */
class PanelTextureBridge(
    val content: View,
    val contentW: Int,
    val contentH: Int,
    private val engine: Engine
) {
    val texture: Texture
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var pixels: ByteBuffer? = null
    private var dirty = true
    private var grace = 0
    private var released = false
    var uploads = 0L
        private set

    init {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(contentH, View.MeasureSpec.EXACTLY)
        )
        content.layout(0, 0, contentW, contentH)
        texture = Texture.Builder()
            .width(contentW)
            .height(contentH)
            .levels(1)
            .format(Texture.InternalFormat.RGBA_8)
            .usage(Texture.Usage.COLOR)
            .build(engine)
        ensureBuffers()
    }

    private fun ensureBuffers() {
        if (bitmap?.width == contentW && bitmap?.height == contentH) return
        runCatching { bitmap?.recycle() }
        bitmap = Bitmap.createBitmap(contentW, contentH, Bitmap.Config.ARGD_8888)
        canvas = Canvas(bitmap!!)
        pixels = ByteBuffer.allocateDirect(contentW * contentH * 4).order(ByteOrder.nativeOrder())
    }

    fun relayout(w: Int, h: Int) {
        if (w == contentW && h == contentH) return
        // sizes are fixed per texture; panels that need a new size are rebuilt
    }

    fun markDirty() {
        dirty = true
        if (grace < 20) grace = 20
    }

    /** Called once per rendered frame by the renderer. */
    fun tick(allowRedraw: Boolean) {
        if (released) return
        if (dirty && allowRedraw) redraw()
        else if (grace > 0 && allowRedraw) { grace--; redraw() }
    }

    private fun redraw() {
        val b = bitmap ?: return
        val c = canvas ?: return
        b.eraseColor(Color.TRANSPARENT)
        try {
            content.draw(c)
        } catch (t: Throwable) {
            Logx.w("Panel3D", "content draw failed", t)
        }
        val buf = pixels ?: return
        buf.rewind()
        b.copyPixelsToBuffer(buf)
        buf.rewind()
        try {
            texture.setImage(engine, 0,
                Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
            uploads++
        } catch (t: Throwable) {
            Logx.w("Panel3D", "texture upload failed", t)
        }
        dirty = false
    }

    /** Dispatches a content-space touch; returns whether content consumed it. */
    fun dispatchTouch(x: Float, y: Float, action: Int): Boolean {
        val ev = MotionEvent.obtain(
            android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
            action, x.coerceIn(0f, contentW.toFloat() - 1), y.coerceIn(0f, contentH.toFloat() - 1), 0
        )
        val handled = try {
            content.dispatchTouchEvent(ev)
        } catch (t: Throwable) {
            false
        }
        ev.recycle()
        if (handled) markDirty()
        return handled
    }

    fun release() {
        released = true
        runCatching { bitmap?.recycle() }
        bitmap = null
        canvas = null
        pixels = null
        runCatching { engine.destroyTexture(texture) }
    }

    companion object {
        /** Hosts content views invisibly so WebView-like children work. */
        fun attachHost(host: FrameLayout, content: View, w: Int, h: Int) {
            content.layoutParams?.let { }
            host.addView(content, FrameLayout.LayoutParams(w, h))
        }
    }
}
