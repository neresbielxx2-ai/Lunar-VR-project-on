package com.agusvr.hand

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Derives finger states and discrete gestures from the 21 MediaPipe landmarks
 * (module: AgusHandTracking).
 *
 * All computations run on the rotated (display-orientation) normalized image
 * space so they are orientation independent.
 */
object GestureRecognizer {

    private const val CURL_EPS = 0.82f

    /** Returns [thumb, index, middle, ring, little] extended flags. */
    fun fingerStates(lm: List<PointF>): BooleanArray {
        if (lm.size < 21) return BooleanArray(5)
        val wrist = lm[0]

        fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)

        // Four fingers: extended when the tip is clearly farther from the wrist
        // than the PIP joint.
        val pairs = arrayOf(
            intArrayOf(8, 6),    // index  tip/pip
            intArrayOf(12, 10),  // middle
            intArrayOf(16, 14),  // ring
            intArrayOf(20, 18)   // little
        )
        val states = BooleanArray(5)
        val handScale = dist(wrist, lm[9]).coerceAtLeast(0.001f) // wrist→middle MCP
        for ((i, p) in pairs.withIndex()) {
            val tipD = dist(wrist, lm[p[0]])
            val pipD = dist(wrist, lm[p[1]])
            states[i + 1] = tipD > pipD * CURL_EPS + handScale * 0.12f
        }

        // Thumb: extended when the tip is far from the index MCP relative to
        // the IP joint distance (works in most orientations).
        val thumbTipD = dist(lm[4], lm[5])
        val thumbIpD = dist(lm[3], lm[5])
        states[0] = thumbTipD > thumbIpD * 1.15f

        return states
    }

    fun pinchDistance(lm: List<PointF>): Float =
        if (lm.size < 21) 1f else hypot(lm[4].x - lm[8].x, lm[4].y - lm[8].y)

    /**
     * Classifies the gesture. [pinchRel] is thumb–index distance relative to
     * hand size; small values mean a real pinch.
     */
    fun classify(lm: List<PointF>, fingers: BooleanArray): Gesture {
        if (lm.size < 21) return Gesture.NONE
        val handScale = hypot(lm[0].x - lm[9].x, lm[0].y - lm[9].y).coerceAtLeast(0.0001f)
        val pinchRel = pinchDistance(lm) / handScale

        val extendedCount = fingers.count { it }

        return when {
            pinchRel < 0.45f && extendedCount <= 3 -> Gesture.PINCH
            extendedCount >= 4 -> Gesture.OPEN
            fingers[1] && !fingers[2] && !fingers[3] && !fingers[4] -> Gesture.POINT
            extendedCount == 0 -> Gesture.GRAB
            fingers[0] && extendedCount == 1 -> Gesture.THUMBS_UP
            fingers[1] -> Gesture.POINT // index extended, thumb ambiguous
            else -> Gesture.NONE
        }
    }

    /** Screen-space pointing direction from the index finger (PIP → TIP). */
    fun rayDirection(pip: PointF, tip: PointF): PointF {
        val dx = tip.x - pip.x
        val dy = tip.y - pip.y
        val len = hypot(dx, dy)
        if (len < 1e-4f) return PointF(0f, -1f)
        return PointF(dx / len, dy / len)
    }

    /**
     * Rough proximity 0..1: apparent palm size relative to the frame.
     * Bigger hand on screen ⇒ closer to the camera.
     */
    fun proximity(lm: List<PointF>): Float {
        if (lm.size < 21) return 0f
        val palm = hypot(lm[0].x - lm[9].x, lm[0].y - lm[9].y)
        return (palm * 6f).coerceIn(0f, 1f)
    }

    /** Jitter metric: mean displacement between two frames (screen px). */
    fun jitter(prev: List<PointF>?, cur: List<PointF>): Float {
        if (prev == null || prev.size != cur.size) return Float.MAX_VALUE
        var sum = 0f
        for (i in cur.indices) sum += hypot(cur[i].x - prev[i].x, cur[i].y - prev[i].y)
        return sum / cur.size
    }

    fun isStable(jitterPx: Float, thresholdPx: Float): Boolean = jitterPx < thresholdPx

    @Suppress("unused")
    private fun absf(v: Float) = abs(v)
}
