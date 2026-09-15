package com.agusvr.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.agusvr.util.Logx
import kotlin.math.max
import kotlin.math.min

/**
 * Head-movement parallax (module: AgusSpatialUI).
 *
 * Uses the rotation-vector sensor (accelerometer fallback) to shift the whole
 * spatial world slightly against the device tilt, giving the floating panels
 * real depth. Disabled automatically when no sensor exists or in Performance
 * mode.
 */
class GyroParallax(context: Context) : SensorEventListener {

    interface Callback {
        /** Offsets in degrees, already smoothed and clamped. */
        fun onParallax(yawDeg: Float, pitchDeg: Float)
    }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val matrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var callback: Callback? = null
    private var enabled = true
    private var smoothYaw = 0f
    private var smoothPitch = 0f
    private var baselineYaw: Float? = null
    private var baselinePitch: Float? = null

    val supported: Boolean get() = rotationSensor != null || accelSensor != null

    fun start(cb: Callback) {
        callback = cb
        if (!enabled || sm == null) return
        val sensor = rotationSensor ?: accelSensor ?: return
        val rate = if (rotationSensor != null) SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_UI
        try {
            sm.registerListener(this, sensor, rate)
        } catch (t: Throwable) {
            Logx.w("Parallax", "sensor register failed", t)
        }
    }

    fun stop() {
        try {
            sm?.unregisterListener(this)
        } catch (_: Throwable) {
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            stop()
            smoothYaw = 0f
            smoothPitch = 0f
            callback?.onParallax(0f, 0f)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val cb = callback ?: return
        if (!enabled) return
        var yaw = 0f
        var pitch = 0f
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            SensorManager.getOrientation(matrix, orientation)
            yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
            pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        } else {
            // accelerometer fallback: tilt estimation
            val x = event.values.getOrElse(0) { 0f }
            val y = event.values.getOrElse(1) { 0f }
            val z = event.values.getOrElse(2) { 9.81f }
            pitch = Math.toDegrees(Math.atan2(y.toDouble(), max(0.001, kotlin.math.hypot(x.toDouble(), z.toDouble())))).toFloat()
            yaw = Math.toDegrees(Math.atan2(x.toDouble(), max(0.001, z.toDouble()))).toFloat()
        }
        // wrap yaw to -180..180
        while (yaw > 180f) yaw -= 360f
        while (yaw < -180f) yaw += 360f

        if (baselineYaw == null) {
            baselineYaw = yaw
            baselinePitch = pitch
        }
        var dy = yaw - (baselineYaw ?: yaw)
        while (dy > 180f) dy -= 360f
        while (dy < -180f) dy += 360f
        val dp = pitch - (baselinePitch ?: pitch)

        // deadzone + clamp + smoothing
        val maxYaw = 5.5f
        val maxPitch = 4.0f
        val targetYaw = min(maxYaw, max(-maxYaw, dy))
        val targetPitch = min(maxPitch, max(-maxPitch, dp))
        smoothYaw += (targetYaw - smoothYaw) * 0.08f
        smoothPitch += (targetPitch - smoothPitch) * 0.08f
        cb.onParallax(smoothYaw, smoothPitch)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
