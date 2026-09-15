package com.agusvr.point

import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.agusvr.hand.Gesture
import com.agusvr.hand.HandFrame
import com.agusvr.hand.HandSnapshot
import com.agusvr.hand.HandTrackingEngine
import com.agusvr.settings.SettingsRepo
import com.agusvr.util.Logx
import com.agusvr.util.Ui
import com.agusvr.util.findViewAt
import com.agusvr.util.screenRect
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pointing ray + proximity selection engine (modules: AgusPointInteraction,
 * AgusInteraction).
 *
 * Consumes [HandFrame]s and produces:
 *  - a screen-space ray from the index fingertip (drawn by the overlay with a
 *    small circle at its tip);
 *  - hover resolution against the real view tree (works with 3D-transformed
 *    floating windows, WebViews, sliders — anything Android can hit-test);
 *  - dwell-to-select with debounce, minimum distance and stability checks —
 *    a fast pass of the ray over an element does NOT open it;
 *  - pinch = instant selection; fist (grab) = drag & drop through synthesized
 *    touch events, so windows can be grabbed and moved with the hand.
 *
 * Real touches always take priority: while the screen is being touched the
 * synthesized stream pauses to avoid double input.
 */
class PointInteraction(private val root: ViewGroup) : HandTrackingEngine.Listener {

    interface TestListener {
        fun onPointDetected(hand: HandSnapshot) {}
        fun onApproachProgress(progress: Float) {}
        fun onSelect(target: View?, via: String) {}
        fun onGrabStart(target: View?) {}
        fun onGrabMove(dxPx: Float, dyPx: Float, totalPx: Float) {}
        fun onGrabRelease(totalPx: Float) {}
        fun onMenuOpened(target: View) {}
    }

    var testListener: TestListener? = null

    /** Set by the activity while a real finger is on the screen. */
    @Volatile
    var realTouchActive: Boolean = false
    private var lastRealTouchMs = 0L

    // ---- state visible to the overlay ----
    @Volatile var activeHand: HandSnapshot? = null
        private set
    @Volatile var rayOrigin: PointF? = null
        private set
    @Volatile var rayEnd: PointF? = null
        private set
    @Volatile var interactionPoint: PointF? = null
        private set
    @Volatile var hoverView: View? = null
        private set
    @Volatile var hoverRect: Rect? = null
        private set
    @Volatile var dwellProgress: Float = 0f
        private set
    @Volatile var grabActive: Boolean = false
        private set

    // ---- internals ----
    private var prevGesture: Gesture = Gesture.NONE
    private var prevFingertip: PointF? = null
    private var dwellTarget: View? = null
    private var dwellStartMs = 0L
    private var lastDwellPoint: PointF? = null
    private var lastTapMs = 0L
    private var downTime = 0L
    private var grabStartPoint: PointF? = null
    private var grabTravelPx = 0f
    private var hoverDispatchedView: View? = null

    private val tmpPoint = PointF()

    override fun onHandFrame(frame: HandFrame) {
        val now = SystemClock.uptimeMillis()
        if (realTouchActive || now - lastRealTouchMs < 350L) {
            clearInteraction()
            return
        }
        // Prefer a pointing hand; else any visible hand.
        val hand = frame.pointing ?: frame.pinching ?: frame.grabbing ?: frame.primary
        activeHand = hand
        if (hand == null) {
            prevGesture = Gesture.NONE
            clearInteraction()
            return
        }

        val tip = hand.fingertipScreen
        val dir = hand.rayDirScreen
        val diag = hypot(root.width.toFloat(), root.height.toFloat())
        val rayLen = diag * 0.42f * SettingsRepo.rayLength

        rayOrigin = PointF(tip.x, tip.y)
        tmpPoint.set(tip.x + dir.x * rayLen, tip.y + dir.y * rayLen)
        rayEnd = PointF(tmpPoint.x, tmpPoint.y)

        when (hand.gesture) {
            Gesture.POINT -> handlePointing(hand, tip, now)
            Gesture.PINCH -> handlePinch(hand, tip, now)
            Gesture.GRAB -> handleGrab(hand, tip, now)
            Gesture.OPEN, Gesture.THUMBS_UP, Gesture.NONE -> {
                if (prevGesture == Gesture.GRAB) endGrab()
                dwellProgress = 0f
                dwellTarget = null
                interactionPoint = null
                updateHover(null, null)
            }
        }
        prevGesture = hand.gesture
        prevFingertip = PointF(tip.x, tip.y)
        if (hand.gesture == Gesture.POINT) testListener?.onPointDetected(hand)
    }

    // ------------------------------------------------------------------
    // POINT: ray hover + dwell selection by proximity
    // ------------------------------------------------------------------

    private fun handlePointing(hand: HandSnapshot, tip: PointF, now: Long) {
        if (grabActive) endGrab()
        val end = rayEnd ?: return

        // Hit-test the ray tip circle first, then the ray midpoint, then the
        // fingertip itself — the circle marks the interaction point.
        var hitPoint = PointF(end.x, end.y)
        var target = findViewAt(root, end.x, end.y)
        if (target == null) {
            val midX = (tip.x + end.x) / 2f
            val midY = (tip.y + end.y) / 2f
            target = findViewAt(root, midX, midY)
            if (target != null) hitPoint = PointF(midX, midY)
        }
        if (target == null) {
            target = findViewAt(root, tip.x, tip.y)
            if (target != null) hitPoint = PointF(tip.x, tip.y)
        }
        interactionPoint = hitPoint
        updateHover(target, target?.screenRect())

        if (target == null) {
            dwellProgress = 0f
            dwellTarget = null
            lastDwellPoint = null
            return
        }

        // Proximity: the fingertip must be reasonably close to the element for
        // the dwell to count ("seleção por aproximação"), and the ray must be
        // stable. Moving away cancels the dwell.
        val diag = hypot(root.width.toFloat(), root.height.toFloat())
        val maxApproachPx = diag * 0.22f * SettingsRepo.selectDistance * SettingsRepo.pointSensitivity
        val fingerDist = hypot(tip.x - hitPoint.x, tip.y - hitPoint.y)
        val nearEnough = fingerDist <= maxApproachPx || hand.proximity > 0.55f

        val stable = lastDwellPoint?.let {
            hypot(it.x - hitPoint.x, it.y - hitPoint.y) < root.dpStabilityPx()
        } ?: true

        if (dwellTarget != target) {
            dwellTarget = target
            dwellStartMs = now
            dwellProgress = 0f
        }
        if (!nearEnough || !stable) {
            if (!stable) dwellStartMs = now // unstable pointing keeps resetting
            dwellProgress = if (nearEnough) dwellProgress else 0f
            testListener?.onApproachProgress(dwellProgress)
            lastDwellPoint = PointF(hitPoint.x, hitPoint.y)
            return
        }

        val dwellMs = max(150, SettingsRepo.dwellMs)
        dwellProgress = ((now - dwellStartMs).toFloat() / dwellMs).coerceIn(0f, 1f)
        testListener?.onApproachProgress(dwellProgress)
        lastDwellPoint = PointF(hitPoint.x, hitPoint.y)

        if (dwellProgress >= 1f) {
            activate(target, hitPoint, "dwell")
            dwellStartMs = now + 500L // debounce re-trigger
            dwellProgress = 0f
        }

        // Keep hover state alive in the view tree (drives CSS :hover, View hover
        // drawables and WebView hover events).
        dispatchHover(hitPoint.x, hitPoint.y)
    }

    // ------------------------------------------------------------------
    // PINCH: instant selection (with debounce)
    // ------------------------------------------------------------------

    private fun handlePinch(hand: HandSnapshot, tip: PointF, now: Long) {
        if (grabActive) endGrab()
        if (prevGesture == Gesture.PINCH) return // act on transition only
        if (now - lastTapMs < 400L) return
        val end = rayEnd ?: tip
        val target = findViewAt(root, end.x, end.y)
            ?: findViewAt(root, (tip.x + end.x) / 2f, (tip.y + end.y) / 2f)
            ?: findViewAt(root, tip.x, tip.y)
        val point = if (target != null) {
            val r = target.screenRect()
            PointF(
                ((tip.x + end.x) / 2f).coerceIn(r.left.toFloat(), r.right.toFloat()),
                ((tip.y + end.y) / 2f).coerceIn(r.top.toFloat(), r.bottom.toFloat())
            )
        } else {
            PointF(end.x, end.y)
        }
        interactionPoint = point
        updateHover(target, target?.screenRect())
        if (target != null) activate(target, point, "pinch")
    }

    // ------------------------------------------------------------------
    // GRAB: drag windows/objects with a closed fist
    // ------------------------------------------------------------------

    private fun handleGrab(hand: HandSnapshot, tip: PointF, now: Long) {
        dwellProgress = 0f
        val end = rayEnd ?: tip
        if (!grabActive) {
            if (prevGesture == Gesture.GRAB) return // wait for a fresh transition
            val target = findViewAt(root, tip.x, tip.y) ?: findViewAt(root, end.x, end.y)
            updateHover(target, target?.screenRect())
            grabStartPoint = PointF(tip.x, tip.y)
            grabTravelPx = 0f
            grabActive = true
            synthTouch(MotionEvent.ACTION_DOWN, tip.x, tip.y)
            testListener?.onGrabStart(target)
        } else {
            val prev = prevFingertip
            if (prev != null) {
                val dx = tip.x - prev.x
                val dy = tip.y - prev.y
                grabTravelPx += hypot(dx, dy)
                synthTouch(MotionEvent.ACTION_MOVE, tip.x, tip.y)
                testListener?.onGrabMove(dx, dy, grabTravelPx)
            }
        }
    }

    private fun endGrab() {
        if (!grabActive) return
        grabActive = false
        val p = prevFingertip ?: grabStartPoint
        if (p != null) synthTouch(MotionEvent.ACTION_UP, p.x, p.y)
        testListener?.onGrabRelease(grabTravelPx)
        grabStartPoint = null
    }

    // ------------------------------------------------------------------
    // Activation & synthesized input
    // ------------------------------------------------------------------

    private fun activate(target: View, at: PointF, via: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapMs < 300L) return // debounce double activations
        lastTapMs = now
        synthTouch(MotionEvent.ACTION_DOWN, at.x, at.y)
        synthTouch(MotionEvent.ACTION_UP, at.x, at.y)
        Ui.tick(target, strong = true)
        Ui.blip(high = true)
        testListener?.onSelect(target, via)
        if (target.tag is String && (target.tag as String).startsWith("hl_menu")) {
            testListener?.onMenuOpened(target)
        }
        Logx.d("Point", "activated ${target.javaClass.simpleName} via $via at (${at.x.toInt()},${at.y.toInt()})")
    }

    private fun synthTouch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
        try {
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            root.dispatchTouchEvent(e)
        } catch (t: Throwable) {
            Logx.w("Point", "synth touch failed", t)
        } finally {
            e.recycle()
        }
    }

    private fun dispatchHover(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        try {
            e.source = InputDevice.SOURCE_MOUSE
            root.dispatchGenericMotionEvent(e)
        } catch (t: Throwable) {
            Logx.w("Point", "hover dispatch failed", t)
        } finally {
            e.recycle()
        }
    }

    private fun dispatchHoverExit() {
        if (hoverDispatchedView == null) return
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_EXIT, 0f, 0f, 0)
        try {
            e.source = InputDevice.SOURCE_MOUSE
            root.dispatchGenericMotionEvent(e)
        } catch (_: Throwable) {
        } finally {
            e.recycle()
        }
        hoverDispatchedView = null
    }

    private fun updateHover(view: View?, rect: Rect?) {
        if (view !== hoverView) {
            if (hoverView != null && view == null) dispatchHoverExit()
            hoverView = view
            hoverDispatchedView = view
        }
        hoverRect = rect
    }

    private fun clearInteraction() {
        activeHand = null
        rayOrigin = null
        rayEnd = null
        interactionPoint = null
        dwellProgress = 0f
        if (grabActive) endGrab()
        updateHover(null, null)
    }

    fun onRealTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                realTouchActive = true
                clearInteraction()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                realTouchActive = false
                lastRealTouchMs = SystemClock.uptimeMillis()
            }
        }
    }

    fun release() {
        clearInteraction()
        testListener = null
    }

    private fun ViewGroup.dpStabilityPx(): Float =
        resources.displayMetrics.density * 14f * SettingsRepo.pointSensitivity.coerceAtLeast(0.5f)
}
