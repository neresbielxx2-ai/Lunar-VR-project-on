package com.agusvr.hand

import android.graphics.PointF

/** Hand tracking data models (module: AgusHandTracking). */

enum class Handedness { LEFT, RIGHT, UNKNOWN }

enum class Gesture(val label: String) {
    NONE("—"),
    OPEN("mão aberta"),
    POINT("apontar"),
    PINCH("pinch"),
    GRAB("grab"),
    THUMBS_UP("joinha")
}

/**
 * One tracked hand.
 *
 * [landmarksNorm] are MediaPipe normalized coordinates (0..1) in the rotated
 * analysis-image space; [landmarksScreen] are the same points mapped to screen
 * pixels (already cover-fit corrected for the camera preview).
 */
data class HandSnapshot(
    val handedness: Handedness,
    val handednessScore: Float,
    val landmarksNorm: List<PointF>,          // 21 points
    val landmarksScreen: List<PointF>,        // 21 points, screen px
    val fingersExtended: BooleanArray,        // thumb, index, middle, ring, little
    val gesture: Gesture,
    val confidence: Float,
    /** Index fingertip in screen px (the ray origin / reticle center). */
    val fingertipScreen: PointF,
    /** Pointing direction in screen space, normalized. */
    val rayDirScreen: PointF,
    /** Rough hand "depth" 0..1 — palm size relative to the frame (bigger = closer). */
    val proximity: Float,
    val timestampMs: Long
) {
    val wrist: PointF get() = landmarksScreen.getOrElse(0) { PointF() }
    val indexTip: PointF get() = landmarksScreen.getOrElse(8) { fingertipScreen }
    val indexPip: PointF get() = landmarksScreen.getOrElse(6) { fingertipScreen }
    val thumbTip: PointF get() = landmarksScreen.getOrElse(4) { fingertipScreen }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandSnapshot) return false
        return timestampMs == other.timestampMs && handedness == other.handedness
    }

    override fun hashCode(): Int = timestampMs.hashCode() * 31 + handedness.ordinal
}

/** Full result for one camera frame. */
data class HandFrame(
    val hands: List<HandSnapshot>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inferenceLatencyMs: Long,
    val timestampMs: Long
) {
    val primary: HandSnapshot? get() = hands.firstOrNull { it.gesture != Gesture.NONE } ?: hands.firstOrNull()
    val pointing: HandSnapshot? get() = hands.firstOrNull { it.gesture == Gesture.POINT }
    val pinching: HandSnapshot? get() = hands.firstOrNull { it.gesture == Gesture.PINCH }
    val grabbing: HandSnapshot? get() = hands.firstOrNull { it.gesture == Gesture.GRAB }
    val isEmpty: Boolean get() = hands.isEmpty()
}

/** Standard MediaPipe 21-landmark skeleton connections. */
object HandSkeleton {
    val CONNECTIONS: Array<IntArray> = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),          // thumb
        intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),          // index
        intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),     // middle
        intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),   // ring
        intArrayOf(13, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),  // little
        intArrayOf(0, 17)                                                                  // palm
    )
    const val WRIST = 0
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_TIP = 8
    const val MIDDLE_TIP = 12
    const val RING_TIP = 16
    const val LITTLE_TIP = 20
}
