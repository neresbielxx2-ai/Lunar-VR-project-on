package com.agusvr.performance

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.agusvr.runtime.RuntimeCore
import com.agusvr.settings.SettingsRepo
import com.agusvr.util.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live performance telemetry + automatic quality scaler (module: AgusPerformance).
 *
 * The VR activity feeds frames through [onFrame]; the monitor publishes
 * [PerfSample]s at 1 Hz and adapts [QualityProfile] when the device is
 * struggling (FPS below target) or has headroom.
 */
object PerformanceMonitor {

    private lateinit var appContext: Context
    private val handler = Handler(Looper.getMainLooper())

    private val _sample = MutableStateFlow(
        PerfSample(0f, 0f, 0, 0, -1, false, 0f, 0f, "inativo", 0, 0L)
    )
    val sample: StateFlow<PerfSample> = _sample

    private val _profile = MutableStateFlow(QualityProfile.current(0))
    val profile: StateFlow<QualityProfile> = _profile

    private val _autoEvents = MutableStateFlow<List<String>>(emptyList())
    val autoEvents: StateFlow<List<String>> = _autoEvents

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps

    private var running = false
    private var frames = 0
    private var windowStartNs = 0L
    private var autoLevel = 0
    private var lowStreak = 0
    private var highStreak = 0
    private var lastCpuTime = 0L
    private var lastWallTime = 0L

    private var batteryReceiver: BroadcastReceiver? = null
    @Volatile private var batteryPct = -1
    @Volatile private var batteryCharging = false
    @Volatile private var temperatureC = 0f

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun start() {
        if (running) return
        running = true
        frames = 0
        windowStartNs = System.nanoTime()
        autoLevel = 0
        lowStreak = 0
        highStreak = 0
        publishProfile()
        registerBattery()
        handler.post(sampleTask)
        Logx.i("Perf", "monitor started (mode=${SettingsRepo.perfMode})")
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(sampleTask)
        unregisterBattery()
        Logx.i("Perf", "monitor stopped")
    }

    fun shutdown() {
        stop()
    }

    /** Called by the VR renderer for every choreographer frame. */
    fun onFrame() {
        if (!running) return
        frames++
    }

    private val sampleTask = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val nowNs = System.nanoTime()
                val elapsedNs = (nowNs - windowStartNs).coerceAtLeast(1)
                val fpsNow = frames * 1_000_000_000f / elapsedNs
                frames = 0
                windowStartNs = nowNs
                _fps.value = fpsNow

                val rt = Runtime.getRuntime()
                val usedMb = ((rt.totalMemory() - rt.freeMemory()) / 1048576L).toInt()
                val maxMb = (rt.maxMemory() / 1048576L).toInt()

                val cpuPct = computeCpuPct(nowNs)

                _sample.value = PerfSample(
                    fps = fpsNow,
                    frameMs = if (fpsNow > 0.5f) 1000f / fpsNow else 0f,
                    memUsedMb = usedMb,
                    memMaxMb = maxMb,
                    batteryPct = batteryPct,
                    batteryCharging = batteryCharging,
                    temperatureC = temperatureC,
                    cpuAppPct = cpuPct,
                    handState = RuntimeCore.handState.value.label,
                    autoLevel = autoLevel,
                    timestamp = nowNs
                )

                autoScale(fpsNow)
            } catch (t: Throwable) {
                Logx.w("Perf", "sample failed", t)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun computeCpuPct(nowNs: Long): Float {
        val cpu = Process.getElapsedCpuTime()
        if (lastWallTime == 0L) {
            lastCpuTime = cpu
            lastWallTime = nowNs
            return 0f
        }
        val dWall = (nowNs - lastWallTime) / 1_000_000f
        val dCpu = (cpu - lastCpuTime).toFloat()
        lastCpuTime = cpu
        lastWallTime = nowNs
        if (dWall <= 0f) return 0f
        return (dCpu / dWall * 100f).coerceIn(0f, Runtime.getRuntime().availableProcessors() * 100f)
    }

    private fun autoScale(fpsNow: Float) {
        if (!SettingsRepo.autoQuality) return
        if (fpsNow < 1f) return // app backgrounded / no frames yet
        val target = _profile.value.targetFps.toFloat()
        if (fpsNow < target - 8f || temperatureC > 43f) {
            lowStreak++
            highStreak = 0
        } else if (fpsNow > target - 2f && temperatureC < 40f) {
            highStreak++
            lowStreak = 0
        } else {
            lowStreak = 0
            highStreak = 0
        }
        if (lowStreak >= 4 && autoLevel > -2) {
            autoLevel--
            lowStreak = 0
            logAuto("carga alta → qualidade reduzida (nível $autoLevel)")
            publishProfile()
        } else if (highStreak >= 10 && autoLevel < 2) {
            autoLevel++
            highStreak = 0
            logAuto("desempenho estável → qualidade aumentada (nível $autoLevel)")
            publishProfile()
        }
    }

    private fun logAuto(msg: String) {
        val line = "${System.currentTimeMillis()} · $msg"
        _autoEvents.value = (listOf(line) + _autoEvents.value).take(24)
    }

    fun publishProfile() {
        _profile.value = QualityProfile.current(autoLevel)
    }

    /** Called when the user changes performance-related settings. */
    fun onSettingsChanged() {
        publishProfile()
    }

    private fun registerBattery() {
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    // Battery temperature in tenths of a degree Celsius (0 when unavailable).
                    val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                    temperatureC = temp / 10f
                }
            }
            appContext.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiver = r
        } catch (t: Throwable) {
            Logx.w("Perf", "battery receiver failed", t)
        }
    }

    private fun unregisterBattery() {
        batteryReceiver?.let {
            try {
                appContext.unregisterReceiver(it)
            } catch (_: Throwable) {
            }
        }
        batteryReceiver = null
    }

    fun nativeHeapMb(): Int = (Debug.getNativeHeapAllocatedSize() / 1048576L).toInt()

    fun systemMemoryInfo(): Pair<Int, Int> {
        return try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            val total = (info.totalMem / 1048576L).toInt()
            val avail = (info.availMem / 1048576L).toInt()
            avail to total
        } catch (_: Throwable) {
            0 to 0
        }
    }
}
