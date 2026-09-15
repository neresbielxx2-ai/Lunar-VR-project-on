package com.agusvr.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.agusvr.R
import com.agusvr.runtime.RuntimeCore
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Ui
import com.agusvr.util.dp

/**
 * Custom animated boot screen shown before the VR engine starts
 * (module: AgusSpatialUI identity). Logo animation + honest boot steps that
 * reflect what the runtime is actually doing, then fades into the shell.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var progressAnim: ValueAnimator? = null

    private val steps = listOf(
        R.string.boot_init to 250L,
        R.string.boot_caps to 700L,
        R.string.boot_camera to 500L,
        R.string.boot_hand to 500L,
        R.string.boot_spatial to 550L,
        R.string.boot_ready to 400L
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.parseColor("#04070E"))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val logo = SplashLogoView(this)
        col.addView(logo, LinearLayout.LayoutParams(dp(170f), dp(170f)))

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(AgusWidgets.TEXT)
            textSize = 26f
            typeface = Ui.display
            letterSpacing = 0.34f
            gravity = Gravity.CENTER
            alpha = 0f
        }
        col.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(26f) })

        val tagline = TextView(this).apply {
            text = getString(R.string.app_tagline)
            setTextColor(AgusWidgets.FAINT)
            textSize = 10f
            typeface = Ui.mono
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            alpha = 0f
        }
        col.addView(tagline, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8f) })

        val stepText = AgusWidgets.monoText(this, getString(R.string.boot_init), AgusWidgets.CYAN, 9.5f)
        stepText.gravity = Gravity.CENTER
        col.addView(stepText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(40f) })

        val bar = AgusWidgets.progressBar(this)
        col.addView(bar, LinearLayout.LayoutParams(dp(210f), dp(3f)).apply {
            topMargin = dp(10f)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))

        val version = AgusWidgets.monoText(this, "v${RuntimeCore.VERSION}", AgusWidgets.FAINT, 8f)
        root.addView(version, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(18f) })

        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        logo.playIntro()
        title.animate().alpha(1f).setDuration(700).setStartDelay(250).start()
        tagline.animate().alpha(1f).setDuration(700).setStartDelay(500).start()

        var total = 0L
        steps.forEach { total += it.second }
        progressAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = LinearInterpolator()
            addUpdateListener { AgusWidgets.setProgress(bar, it.animatedValue as Float) }
            start()
        }

        var acc = 0L
        for ((i, step) in steps.withIndex()) {
            acc += step.second
            handler.postDelayed({
                stepText.text = getString(step.first)
                if (i == 2 && RuntimeCore.capabilities?.hasRearCamera != true) {
                    stepText.text = getString(R.string.boot_camera) + " (ausente — ambiente virtual)"
                }
                if (i == 3 && RuntimeCore.capabilities?.handModelPresent != true) {
                    stepText.text = getString(R.string.boot_hand) + " (modelo ausente — toque ativo)"
                }
            }, acc)
        }
        handler.postDelayed({ goShell() }, acc + 260)
    }

    private var navigated = false

    private fun goShell() {
        if (navigated || isFinishing) return
        navigated = true
        startActivity(Intent(this, ShellActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        progressAnim?.cancel()
        super.onDestroy()
    }
}
