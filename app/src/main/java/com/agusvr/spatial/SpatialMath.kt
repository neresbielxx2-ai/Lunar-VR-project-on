package com.agusvr.spatial

import android.view.View
import com.agusvr.settings.SettingsRepo
import com.agusvr.util.clamp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where an element lives in the VR world (module: AgusSpatialUI). */
data class WorldPose(
    var yaw: Float = 0f,     // degrees, -60..60 (left/right around the user)
    var pitch: Float = 0f,   // degrees, -35..35 (up/down)
    var dist: Float = 1.0f   // 0.7 (near) .. 1.6 (far)
) {
    fun clamp() {
        yaw = yaw.clamp(-62f, 62f)
        pitch = pitch.clamp(-34f, 34f)
        dist = dist.clamp(0.65f, 1.7f)
    }

    fun copyFrom(other: WorldPose) {
        yaw = other.yaw
        pitch = other.pitch
        dist = other.dist
    }
}

/**
 * Maps [WorldPose] to real View transforms so panels and tiles genuinely
 * float in space: perspective rotation, depth scale, shadows and position on
 * a virtual cylinder in front of the user.
 */
object SpatialMath {

    const val TILE_SPREAD = 1.0f

    /**
     * Applies the pose to [v] inside a container of [w]×[h] pixels.
     * [globalScale] multiplies the user's interface size/distance settings.
     */
    fun apply(v: View, pose: WorldPose, w: Int, h: Int, globalScale: Float = 1f, shadows: Boolean = true) {
        if (w <= 0 || h <= 0) return
        val zoom = (SettingsRepo.uiScale * globalScale).clamp(0.55f, 1.6f)
        val distance = max(0.4f, pose.dist * SettingsRepo.uiDistance)

        val k = w * 0.62f
        val yawR = Math.toRadians(pose.yaw.toDouble())
        val pitchR = Math.toRadians(pose.pitch.toDouble())

        val tx = (sin(yawR) * k * zoom / distance).toFloat()
        val ty = (sin(pitchR) * k * 0.78f * zoom / distance).toFloat()

        val scale = (zoom / distance).clamp(0.45f, 1.45f)

        v.translationX = tx
        v.translationY = ty
        v.scaleX = scale
        v.scaleY = scale
        v.rotationY = (-pose.yaw * 0.72f).clamp(-46f, 46f)
        v.rotationX = (pose.pitch * 0.5f).clamp(-24f, 24f)
        v.cameraDistance = max(w, h) * 3.2f
        v.elevation = if (shadows) (18f * scale) else 0f
        // Depth cue: far elements get slightly darker via alpha. Window open /
        // close animations store their factor in the plain view tag.
        val anim = v.tag as? Float ?: 1f
        v.alpha = anim * (1f - (distance - 0.7f) * 0.16f).clamp(0.72f, 1f)
    }

    /**
     * Places [v] on a cylinder around the user (cockpit-style): the element
     * yaws/pitches to face the user and gains real depth, so panels wrap
     * around the viewer instead of sliding flat on the screen.
     * [radiusFactor] ~1.0 = tight cockpit, ~1.6 = gentle curve.
     */
    fun applyCylinder(
        v: View, pose: WorldPose, w: Int, h: Int,
        radiusFactor: Float = 1.3f, globalScale: Float = 1f, shadows: Boolean = true
    ) {
        if (w <= 0 || h <= 0) return
        val zoom = (SettingsRepo.uiScale * globalScale).clamp(0.55f, 1.6f)
        val distance = max(0.4f, pose.dist * SettingsRepo.uiDistance)
        val r = w * radiusFactor * distance
        val yawR = Math.toRadians(pose.yaw.toDouble())
        val pitchR = Math.toRadians(pose.pitch.toDouble())

        v.rotationY = (-pose.yaw * 0.94f).clamp(-58f, 58f)
        v.rotationX = (pose.pitch * 0.88f).clamp(-40f, 40f)
        v.translationX = (sin(yawR) * r * 0.94f).toFloat()
        v.translationY = (-sin(pitchR) * r * 0.62f).toFloat()
        // concave wrap: off-center elements come toward the user
        v.translationZ = (((1 - Math.cos(yawR)) * r * 0.40) +
            ((1 - Math.cos(pitchR)) * r * 0.28)).toFloat()
        val scale = (zoom / distance).clamp(0.55f, 1.3f)
        v.scaleX = scale
        v.scaleY = scale
        v.cameraDistance = max(w, h) * 2.6f
        v.elevation = if (shadows) 20f * scale else 0f
        val anim = v.tag as? Float ?: 1f
        v.alpha = anim * (1f - (distance - 0.7f) * 0.14f).clamp(0.74f, 1f)
    }

    fun spreadFactor(w: Int): Float = min(1f, max(0.62f, w / 980f))

    /** Screen size for a window given its dp size and pose. */
    fun windowPixelScale(pose: WorldPose, density: Float): Float {
        val zoom = (SettingsRepo.uiScale).clamp(0.55f, 1.6f)
        return (zoom / max(0.4f, pose.dist * SettingsRepo.uiDistance)).clamp(0.45f, 1.45f)
    }

    fun distanceToPx(distMeters: Float, w: Int): Float = distMeters * w * 0.1f

    fun norm(v: Float, lo: Float, hi: Float): Float = ((v - lo) / max(1e-6f, hi - lo)).clamp(0f, 1f)

    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    fun smoothstep(t: Float): Float {
        val x = t.clamp(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    fun nearlyEqualPose(a: WorldPose, b: WorldPose, eps: Float = 0.35f): Boolean =
        abs(a.yaw - b.yaw) < eps && abs(a.pitch - b.pitch) < eps && abs(a.dist - b.dist) < 0.01f
}
