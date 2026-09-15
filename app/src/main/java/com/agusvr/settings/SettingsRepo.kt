package com.agusvr.settings

import android.content.Context
import android.content.SharedPreferences
import com.agusvr.util.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * All Agus VR settings (module: AgusSettings).
 *
 * Values are read live by the spatial runtime every frame, and a monotonically
 * increasing [version] StateFlow lets panels refresh their controls.
 * Settings are also exportable to Settings/settings.json inside the sandbox.
 */
object SettingsRepo {

    private const val PREFS = "agus_settings"
    private lateinit var prefs: SharedPreferences

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun bump() {
        _version.value += 1
    }

    // ---------------- 3DOF spatial menus (SBS/VR Box) ----------------
    /** Interpupilar distance in millimeters for side-by-side stereo. */
    var ipdMm: Float
        get() = prefs.getFloat("ipd_mm", 63f)
        set(v) = prefs.edit().putFloat("ipd_mm", v.coerceIn(52f, 76f)).apply()

    /** Side-by-side (VR Box/Cardboard) stereo mode for the 3D menu system. */
    var sbsMode: Boolean
        get() = prefs.getBoolean("sbs_mode", false)
        set(v) = prefs.edit().putBoolean("sbs_mode", v).apply()

    // ---------------- Interaction ----------------
    var uiDistance: Float
        get() = prefs.getFloat("ui_distance", 1.0f)
        set(v) { prefs.edit().putFloat("ui_distance", v.coerceIn(0.7f, 1.5f)).apply(); bump() }

    var uiScale: Float
        get() = prefs.getFloat("ui_scale", 1.0f)
        set(v) { prefs.edit().putFloat("ui_scale", v.coerceIn(0.7f, 1.4f)).apply(); bump() }

    var pointSensitivity: Float
        get() = prefs.getFloat("point_sensitivity", 1.0f)
        set(v) { prefs.edit().putFloat("point_sensitivity", v.coerceIn(0.5f, 2.0f)).apply(); bump() }

    var selectDistance: Float
        get() = prefs.getFloat("select_distance", 1.0f)
        set(v) { prefs.edit().putFloat("select_distance", v.coerceIn(0.5f, 2.0f)).apply(); bump() }

    var dwellMs: Int
        get() = prefs.getInt("dwell_ms", 600)
        set(v) { prefs.edit().putInt("dwell_ms", v.coerceIn(250, 1500)).apply(); bump() }

    var rayIntensity: Float
        get() = prefs.getFloat("ray_intensity", 0.85f)
        set(v) { prefs.edit().putFloat("ray_intensity", v.coerceIn(0.2f, 1.0f)).apply(); bump() }

    var rayLength: Float
        get() = prefs.getFloat("ray_length", 1.0f)
        set(v) { prefs.edit().putFloat("ray_length", v.coerceIn(0.4f, 1.8f)).apply(); bump() }

    // ---------------- Hand tracking ----------------
    var handEnabled: Boolean
        get() = prefs.getBoolean("hand_enabled", true)
        set(v) { prefs.edit().putBoolean("hand_enabled", v).apply(); bump() }

    /** "auto" | "gpu" | "cpu" */
    var handDelegate: String
        get() = prefs.getString("hand_delegate", "auto") ?: "auto"
        set(v) { prefs.edit().putString("hand_delegate", v).apply(); bump() }

    // ---------------- Feedback ----------------
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) { prefs.edit().putBoolean("haptics", v).apply(); bump() }

    var audio: Boolean
        get() = prefs.getBoolean("audio", true)
        set(v) { prefs.edit().putBoolean("audio", v).apply(); bump() }

    // ---------------- Visual ----------------
    var effects: Boolean
        get() = prefs.getBoolean("effects", true)
        set(v) { prefs.edit().putBoolean("effects", v).apply(); bump() }

    var shadows: Boolean
        get() = prefs.getBoolean("shadows", true)
        set(v) { prefs.edit().putBoolean("shadows", v).apply(); bump() }

    var particles: Boolean
        get() = prefs.getBoolean("particles", true)
        set(v) { prefs.edit().putBoolean("particles", v).apply(); bump() }

    var debugOverlay: Boolean
        get() = prefs.getBoolean("debug_overlay", false)
        set(v) { prefs.edit().putBoolean("debug_overlay", v).apply(); bump() }

    // ---------------- System ----------------
    /** "performance" | "balanced" | "quality" */
    var perfMode: String
        get() = prefs.getString("perf_mode", "balanced") ?: "balanced"
        set(v) { prefs.edit().putString("perf_mode", v).apply(); bump() }

    var autoQuality: Boolean
        get() = prefs.getBoolean("auto_quality", true)
        set(v) { prefs.edit().putBoolean("auto_quality", v).apply(); bump() }

    var batterySaver: Boolean
        get() = prefs.getBoolean("battery_saver", false)
        set(v) { prefs.edit().putBoolean("battery_saver", v).apply(); bump() }

    /** "low" | "medium" | "high" camera analysis/preview tier. */
    var cameraTier: String
        get() = prefs.getString("camera_tier", "medium") ?: "medium"
        set(v) { prefs.edit().putString("camera_tier", v).apply(); bump() }

    var browserHome: String
        get() = prefs.getString("browser_home", "https://www.google.com") ?: "https://www.google.com"
        set(v) { prefs.edit().putString("browser_home", v.ifBlank { "https://www.google.com" }).apply(); bump() }

    // ---------------- Reset / export ----------------
    fun resetDefaults() {
        prefs.edit().clear().apply()
        bump()
        Logx.i("Settings", "defaults restored")
    }

    fun exportToFile(): File? = try {
        val json = JSONObject()
        for (key in prefs.all.keys) {
            when (val v = prefs.all[key]) {
                is Boolean -> json.put(key, v)
                is Int -> json.put(key, v)
                is Float -> json.put(key, v.toDouble())
                is Long -> json.put(key, v)
                is String -> json.put(key, v)
                else -> {}
            }
        }
        val dir = com.agusvr.storage.AgusPaths.settings
        val f = File(dir, "settings.json")
        f.writeText(json.toString(2))
        f
    } catch (t: Throwable) {
        Logx.w("Settings", "export failed", t)
        null
    }
}
