package com.agusvr.performance

import com.agusvr.settings.SettingsRepo

/** Performance / Balanced / Quality modes (module: AgusPerformance). */
enum class PerfMode(val key: String, val label: String) {
    PERFORMANCE("performance", "Performance"),
    BALANCED("balanced", "Balanced"),
    QUALITY("quality", "Quality");

    companion object {
        fun fromKey(key: String): PerfMode = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

/**
 * Concrete effect budget derived from the selected [PerfMode] plus the
 * automatic scaler level (-2 .. +2) and battery-saver state.
 */
data class QualityProfile(
    val particlesEnabled: Boolean,
    val particleCount: Int,
    val glowEnabled: Boolean,
    val shadowsEnabled: Boolean,
    val parallaxEnabled: Boolean,
    val overlayDetail: Boolean,       // finger joint dots, ray gradient etc.
    val analysisDivisor: Int,         // run hand tracking every N camera frames
    val cameraTier: String,           // low / medium / high
    val targetFps: Int
) {
    companion object {
        fun current(autoLevel: Int = 0): QualityProfile {
            val mode = PerfMode.fromKey(SettingsRepo.perfMode)
            val battery = SettingsRepo.batterySaver
            val level = (autoLevel + (if (battery) -1 else 0)).coerceIn(-2, 2)
            val base = when (mode) {
                PerfMode.PERFORMANCE -> QualityProfile(
                    particlesEnabled = false, particleCount = 0, glowEnabled = false,
                    shadowsEnabled = false, parallaxEnabled = false, overlayDetail = false,
                    analysisDivisor = 3, cameraTier = "low", targetFps = 30
                )
                PerfMode.BALANCED -> QualityProfile(
                    particlesEnabled = true, particleCount = 46, glowEnabled = true,
                    shadowsEnabled = true, parallaxEnabled = true, overlayDetail = true,
                    analysisDivisor = 2, cameraTier = "medium", targetFps = 45
                )
                PerfMode.QUALITY -> QualityProfile(
                    particlesEnabled = true, particleCount = 90, glowEnabled = true,
                    shadowsEnabled = true, parallaxEnabled = true, overlayDetail = true,
                    analysisDivisor = 1, cameraTier = "high", targetFps = 60
                )
            }
            // apply user overrides then auto scaler steps
            var p = base.copy(
                particlesEnabled = base.particlesEnabled && SettingsRepo.particles,
                glowEnabled = base.glowEnabled && SettingsRepo.effects,
                shadowsEnabled = base.shadowsEnabled && SettingsRepo.shadows
            )
            p = when (level) {
                -2 -> p.copy(particlesEnabled = false, glowEnabled = false, shadowsEnabled = false,
                    parallaxEnabled = false, analysisDivisor = 4, targetFps = 30)
                -1 -> p.copy(particleCount = (p.particleCount / 2).coerceAtMost(40), glowEnabled = p.glowEnabled && false,
                    shadowsEnabled = false, analysisDivisor = maxOf(p.analysisDivisor, 3), targetFps = minOf(p.targetFps, 35))
                0 -> p
                1 -> p.copy(particleCount = (p.particleCount * 1.4).toInt().coerceAtMost(110))
                else -> p.copy(particlesEnabled = true, particleCount = 120, glowEnabled = true, shadowsEnabled = true,
                    analysisDivisor = 1, targetFps = 60)
            }
            // user-forced camera tier wins over auto
            val userTier = SettingsRepo.cameraTier
            p = p.copy(cameraTier = if (level <= -1) "low" else userTier)
            return p
        }

        private fun minOf(a: Int, b: Int) = if (a < b) a else b
        private fun maxOf(a: Int, b: Int) = if (a > b) a else b
    }
}

data class PerfSample(
    val fps: Float,
    val frameMs: Float,
    val memUsedMb: Int,
    val memMaxMb: Int,
    val batteryPct: Int,
    val batteryCharging: Boolean,
    val temperatureC: Float,
    val cpuAppPct: Float,
    val handState: String,
    val autoLevel: Int,
    val timestamp: Long
)
