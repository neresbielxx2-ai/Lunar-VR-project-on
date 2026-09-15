package com.agusvr.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import com.agusvr.util.Logx

/**
 * Static device capability probe (module: AgusVRRuntime).
 *
 * Every VR feature checks these flags so that unsupported hardware degrades
 * gracefully instead of crashing (spec §22).
 */
data class DeviceCapabilities(
    val hasRearCamera: Boolean,
    val hasGyroscope: Boolean,
    val hasAccelerometer: Boolean,
    val hasVibrator: Boolean,
    val handModelPresent: Boolean,
    val lowRamDevice: Boolean,
    val totalRamMb: Int,
    val screenW: Int,
    val screenH: Int,
    val densityDpi: Int,
    val smallestWidthDp: Int,
    val androidSdk: Int,
    val hasWebView: Boolean
) {
    val screenClass: String
        get() = when {
            smallestWidthDp >= 720 -> "grande"
            smallestWidthDp >= 480 -> "médio"
            else -> "pequeno"
        }
}

object DeviceProbe {

    fun probe(context: Context): DeviceCapabilities {
        val pm = context.packageManager
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        val hasRear = probeRearCamera(context) ?: pm.hasSystemFeature(PackageManager.FEATURE_CAMERA)

        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val dm = context.resources.displayMetrics
        val swDp = (minOf(dm.widthPixels, dm.heightPixels) / dm.density).toInt()

        val hasWebView = try {
            pm.hasSystemFeature("android.software.webview") ||
                runCatching { Class.forName("android.webkit.WebView") }.isSuccess
        } catch (_: Throwable) {
            false
        }

        return DeviceCapabilities(
            hasRearCamera = hasRear,
            hasGyroscope = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
            hasAccelerometer = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
            hasVibrator = probeVibrator(context),
            handModelPresent = probeHandModel(context),
            lowRamDevice = am?.isLowRamDevice ?: false,
            totalRamMb = ((memInfo.totalMem) / (1024 * 1024)).toInt(),
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            densityDpi = dm.densityDpi,
            smallestWidthDp = swDp,
            androidSdk = Build.VERSION.SDK_INT,
            hasWebView = hasWebView
        )
    }

    private fun probeRearCamera(context: Context): Boolean? = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        var found = false
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) found = true
        }
        found
    } catch (t: Throwable) {
        Logx.w("DeviceProbe", "camera probe failed", t)
        null
    }

    private fun probeVibrator(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 31) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.hasVibrator() == true
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() == true
        }
    } catch (_: Throwable) {
        false
    }

    private fun probeHandModel(context: Context): Boolean = try {
        context.assets.open("models/hand_landmarker.task").use { it.available() > 1_000_000 }
    } catch (_: Throwable) {
        false
    }

    fun batteryLevel(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Throwable) {
            -1
        }
    }
}
