package com.agusvr.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.agusvr.R
import com.agusvr.runtime.RuntimeCore
import com.agusvr.settings.SettingsRepo
import com.agusvr.storage.AgusPaths
import com.agusvr.ui.dialogs.AgusDialogs
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.util.formatBytes

/**
 * The calm shell UI before entering VR (module: AgusSpatialUI identity):
 * no toolbars, no Android chrome — an honest system summary of what this
 * device can do and one clear action: INICIAR VR.
 */
class ShellActivity : AppCompatActivity() {

    private lateinit var statusBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.parseColor("#04070E"))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(30), dp(28), dp(24))
        }

        // ---- identity ----
        val mark = TextView(this).apply {
            text = "A"
            setTextColor(AgusWidgets.CYAN)
            textSize = 30f
            typeface = Ui.display
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_tile)
        }
        col.addView(mark, LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(AgusWidgets.TEXT)
            textSize = 21f
            typeface = Ui.display
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
        }
        col.addView(title, wrapLp().apply { topMargin = dp(16) })

        val tagline = AgusWidgets.monoText(this, getString(R.string.app_tagline), AgusWidgets.FAINT, 9f)
        tagline.gravity = Gravity.CENTER
        col.addView(tagline, wrapLp().apply { topMargin = dp(6) })

        // ---- status card ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        card.addView(AgusWidgets.sectionTitle(this, getString(R.string.shell_status)))
        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(statusBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        col.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(28) })

        // ---- enter VR ----
        val enter = AgusWidgets.primaryButton(this, getString(R.string.shell_enter_vr), R.drawable.ic_ray) {
            startActivity(Intent(this, com.agusvr.vr.VRActivity::class.java))
        }
        enter.textSize = 13f
        enter.setPadding(dp(20), dp(16), dp(20), dp(16))
        col.addView(enter, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })

        // ---- 3DOF spatial menus (world-root panels + SBS/VR Box) ----
        val enter3d = AgusWidgets.ghostButton(this, "ESPAÇO 3D · MENUS 3DOF", R.drawable.ic_orbit) {
            startActivity(Intent(this, com.agusvr.vr3d.Spatial3dActivity::class.java))
        }
        col.addView(enter3d, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        // ---- secondary actions ----
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(AgusWidgets.ghostButton(this, getString(R.string.shell_quick_settings), R.drawable.ic_settings) { quickSettings() })
        row.addView(spaced(AgusWidgets.ghostButton(this, getString(R.string.shell_about), R.drawable.ic_info) {
            AgusDialogs.info(this, getString(R.string.shell_about), RuntimeCore.describe())
        }))
        col.addView(row, wrapLp().apply { topMargin = dp(12) })

        val version = AgusWidgets.monoText(
            this,
            getString(R.string.shell_version_fmt, RuntimeCore.VERSION, "AgusVRRuntime"),
            AgusWidgets.FAINT, 8f
        )
        version.gravity = Gravity.CENTER
        col.addView(version, wrapLp().apply { topMargin = dp(18) })

        val scroll = ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
        }
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))

        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun renderStatus() {
        statusBox.removeAllViews()
        val caps = RuntimeCore.capabilities
        val rows = listOf(
            Pair(
                if (caps?.hasRearCamera == true) getString(R.string.shell_camera_ok) else getString(R.string.shell_camera_missing),
                caps?.hasRearCamera == true
            ),
            Pair(
                if (caps?.handModelPresent == true) getString(R.string.shell_hand_ok) else getString(R.string.shell_hand_missing),
                caps?.handModelPresent == true
            ),
            Pair(
                if (caps?.hasGyroscope == true || caps?.hasAccelerometer == true) getString(R.string.shell_sensors_ok) else getString(R.string.shell_sensors_missing),
                caps?.hasGyroscope == true || caps?.hasAccelerometer == true
            ),
            Pair(
                getString(R.string.shell_storage) + ": " + formatBytes(AgusPaths.freeSpaceBytes()) + " livres",
                true
            )
        )
        for ((label, ok) in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val dot = TextView(this).apply {
                text = if (ok) "●" else "○"
                textSize = 9f
                setTextColor(if (ok) AgusWidgets.MINT else AgusWidgets.AMBER)
            }
            line.addView(dot, LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
            val text = AgusWidgets.bodyText(this, label, if (ok) AgusWidgets.TEXT else AgusWidgets.DIM, 10.5f)
            line.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            statusBox.addView(line, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun quickSettings() {
        val on = "ligado"
        val off = "desligado"
        fun state(b: Boolean) = if (b) on else off
        AgusDialogs.actionSheet(this, getString(R.string.shell_quick_settings), listOf(
            "Rastreamento de mãos — ${if (com.agusvr.runtime.BuildFlags.HAND_TRACKING) state(SettingsRepo.handEnabled) else "em breve"}" to {
                if (com.agusvr.runtime.BuildFlags.HAND_TRACKING) {
                    SettingsRepo.handEnabled = !SettingsRepo.handEnabled
                } else {
                    AgusDialogs.info(this, "Rastreamento de mãos",
                        "Chega em uma atualização futura — esta build é focada na interface espacial 3d curva.")
                }
                quickSettings()
            },
            "Vibração (haptics) — ${state(SettingsRepo.haptics)}" to {
                SettingsRepo.haptics = !SettingsRepo.haptics
                quickSettings()
            },
            "Sons de interface — ${state(SettingsRepo.audio)}" to {
                SettingsRepo.audio = !SettingsRepo.audio
                quickSettings()
            },
            "Efeitos visuais — ${state(SettingsRepo.effects)}" to {
                SettingsRepo.effects = !SettingsRepo.effects
                quickSettings()
            },
            "Sombras espaciais — ${state(SettingsRepo.shadows)}" to {
                SettingsRepo.shadows = !SettingsRepo.shadows
                quickSettings()
            }
        ))
    }

    private fun spaced(v: android.view.View): android.view.View {
        (v.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(10)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = dp(10)
        v.layoutParams = lp
        return v
    }

    private fun wrapLp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
