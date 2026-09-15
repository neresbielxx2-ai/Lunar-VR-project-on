package com.agusvr.vr3d

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.agusvr.util.Logx
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Quaternion helpers for the 3DOF spatial system (module: AgusSpatialUI/3D).
 * Layout: [x, y, z, w].
 */
object QMath {
    fun identity(): FloatArray = floatArrayOf(0f, 0f, 0f, 1f)

    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(4)): FloatArray {
        out[0] = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1]
        out[1] = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0]
        out[2] = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3]
        out[3] = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        return out
    }

    fun conjugate(q: FloatArray, out: FloatArray = FloatArray(4)): FloatArray {
        out[0] = -q[0]; out[1] = -q[1]; out[2] = -q[2]; out[3] = q[3]
        return out
    }

    fun normalize(q: FloatArray) {
        val n = Math.sqrt((q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).toDouble())
        if (n < 1e-8) { q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f; return }
        q[0] = (q[0] / n).toFloat(); q[1] = (q[1] / n).toFloat()
        q[2] = (q[2] / n).toFloat(); q[3] = (q[3] / n).toFloat()
    }

    /** Critical-damped slerp used for jitter-free, low-latency smoothing. */
    fun slerp(a: FloatArray, b: FloatArray, t: Float, out: FloatArray = FloatArray(4)): FloatArray {
        var dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        val bb = b.clone()
        if (dot < 0f) { dot = -dot; bb[0] = -bb[0]; bb[1] = -bb[1]; bb[2] = -bb[2]; bb[3] = -bb[3] }
        if (dot > 0.9995f) {
            out[0] = a[0] + (bb[0] - a[0]) * t; out[1] = a[1] + (bb[1] - a[1]) * t
            out[2] = a[2] + (bb[2] - a[2]) * t; out[3] = a[3] + (bb[3] - a[3]) * t
            normalize(out)
            return out
        }
        val theta = acos(dot.coerceIn(-1f, 1f))
        val s = sin(theta)
        val wa = sin((1f - t) * theta) / s
        val wb = sin(t * theta) / s
        out[0] = a[0] * wa + bb[0] * wb; out[1] = a[1] * wa + bb[1] * wb
        out[2] = a[2] * wa + bb[2] * wb; out[3] = a[3] * wa + bb[3] * wb
        return out
    }

    /** Angle between two orientations in degrees (for micro-jitter deadzone). */
    fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceIn(-1f, 1f)
        return (2.0 * acos(Math.abs(d.toDouble())) * 180.0 / Math.PI).toFloat()
    }

    /** Rotation matrix (column-major, Android/Filament layout) from quaternion. */
    fun toMatrix(q: FloatArray, m: FloatArray, offset: Int = 0) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        m[offset + 0] = 1f - 2f * (yy + zz); m[offset + 1] = 2f * (xy + wz); m[offset + 2] = 2f * (xz - wy); m[offset + 3] = 0f
        m[offset + 4] = 2f * (xy - wz); m[offset + 5] = 1f - 2f * (xx + zz); m[offset + 6] = 2f * (yz + wx); m[offset + 7] = 0f
        m[offset + 8] = 2f * (xz + wy); m[offset + 9] = 2f * (yz - wx); m[offset + 10] = 1f - 2f * (xx + yy); m[offset + 11] = 0f
        m[offset + 12] = 0f; m[offset + 13] = 0f; m[offset + 14] = 0f; m[offset + 15] = 1f
    }

    fun rotateVec(q: FloatArray, v: FloatArray, out: FloatArray = FloatArray(3)): FloatArray {
        // v' = q * v * q*
        val vx = v[0]; val vy = v[1]; val vz = v[2]
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        out[0] = vx + qw * tx + (qy * tz - qz * ty)
        out[1] = vy + qw * ty + (qz * tx - qx * tz)
        out[2] = vz + qw * tz + (qx * ty - qy * tx)
        return out
    }
}

/**
 * Pure 3DOF head orientation: rotation-vector sensor → quaternion, with
 * exponential smoothing (low latency + micro-jitter damping) and explicit
 * recenter. No position, no SLAM, no camera, no ARCore — orientation only.
 */
class HeadTracker3Dof(context: Context) : SensorEventListener {

    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val raw = QMath.identity()
    private val smoothed = QMath.identity()
    private val scratch = FloatArray(4)
    private var lastNs = 0L
    private var hasSample = false

    /** World-root recenter offset: rigQ = recenter⁻¹ ⊗ headQ. */
    private val recenter = QMath.identity()
    private val recenterInv = QMath.identity()
    val rigQuaternion: FloatArray = QMath.identity()

    val available: Boolean get() = sensor != null

    fun start() {
        sensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Logx.i("Head3Dof", "tracking with ${it.name}")
        } ?: Logx.w("Head3Dof", "no rotation-vector sensor — head stays fixed")
    }

    fun stop() = sm.unregisterListener(this)

    /** Current view direction becomes "forward" again. */
    fun recenter() {
        synchronized(smoothed) {
            recenter[0] = smoothed[0]; recenter[1] = smoothed[1]
            recenter[2] = smoothed[2]; recenter[3] = smoothed[3]
            QMath.conjugate(recenter, recenterInv)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.values.size < 4) return
        SensorManager.getQuaternionFromRotationVector(scratch, e.values)
        // Android sensor quats rotate world→device; we want device orientation in world.
        synchronized(smoothed) {
            raw[0] = scratch[0]; raw[1] = scratch[1]; raw[2] = scratch[2]; raw[3] = scratch[3]
            QMath.normalize(raw)
            if (!hasSample) {
                smoothed[0] = raw[0]; smoothed[1] = raw[1]
                smoothed[2] = raw[2]; smoothed[3] = raw[3]
                hasSample = true
            }
        }
        lastNs = e.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Advances the smoothing filter for this frame and refreshes
     * [rigQuaternion]. [dtSec] comes from the render loop clock.
     */
    fun update(dtSec: Float) {
        synchronized(smoothed) {
            if (hasSample) {
                val tau = 0.038f                       // ~38 ms time constant: crisp but stable
                var alpha = 1f - Math.exp((-max(dtSec, 0.001f) / tau).toDouble()).toFloat()
                val dev = QMath.angleDeg(smoothed, raw)
                if (dev < 0.12f) alpha *= 0.25f        // deadzone: kills idle shimmer
                QMath.slerp(smoothed, raw, alpha.coerceIn(0f, 1f), smoothed)
            }
            QMath.multiply(recenterInv, smoothed, rigQuaternion)
        }
    }

    /** Azimuth (+right) / elevation (+up) in degrees the user is looking at. */
    fun lookAngles(): FloatArray {
        val f = QMath.rotateVec(rigQuaternion, floatArrayOf(0f, 0f, -1f))
        val az = Math.toDegrees(Math.atan2(f[0].toDouble(), -f[2].toDouble())).toFloat()
        val el = Math.toDegrees(Math.asin(f[1].toDouble().coerceIn(-1.0, 1.0))).toFloat()
        return floatArrayOf(az, el)
    }
}
