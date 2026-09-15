package com.agusvr.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.util.dpF
import kotlin.math.abs

/**
 * A floating spatial app tile on the VR home (module: AgusHome).
 * Custom identity: glass card, line-art icon in the app accent color,
 * Orbitron label, hover glow + lift animation (works with touch hover AND the
 * synthesized hand hover stream).
 */
class TileView(
    context: Context,
    private val label: String,
    iconRes: Int,
    private val accent: Int
) : LinearLayout(context) {

    private val iconView: ImageView
    private val glow: GradientDrawable
    private var hoverAnim: ValueAnimator? = null
    private var glowLevel = 0f

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundResource(com.agusvr.R.drawable.bg_tile)
        setPadding(dp(10), dp(12), dp(10), dp(10))
        isClickable = true
        isFocusable = true

        val iconHolder = android.widget.FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(9); gravity = Gravity.CENTER_HORIZONTAL }
        }
        glow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
        val glowView = View(context).apply {
            background = glow
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
        }
        iconView = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(accent)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
        }
        iconHolder.addView(glowView)
        iconHolder.addView(iconView)

        val labelView = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#F2F7FF"))
            textSize = 10.5f
            typeface = Ui.display
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            maxLines = 2
        }
        val sub = TextView(context).apply {
            text = "agus://" + label.lowercase().replace(' ', '-')
            setTextColor(Color.parseColor("#668FA3C8"))
            textSize = 7f
            typeface = Ui.mono
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
        }

        addView(iconHolder, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(sub, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    override fun setHovered(hovered: Boolean) {
        val was = isHovered
        super.setHovered(hovered)
        if (was != hovered) animateHover(if (hovered) 1f else 0f)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (pressed) animateHover(1f)
    }

    private fun animateHover(target: Float) {
        hoverAnim?.cancel()
        hoverAnim = ValueAnimator.ofFloat(glowLevel, target).apply {
            duration = if (abs(target - glowLevel) > 0.5f) 180 else 240
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                glowLevel = it.animatedValue as Float
                glow.setColor(
                    Color.argb(
                        (glowLevel * 90).toInt(),
                        Color.red(accent), Color.green(accent), Color.blue(accent)
                    )
                )
                iconView.scaleX = 1f + glowLevel * 0.14f
                iconView.scaleY = 1f + glowLevel * 0.14f
                elevation = dpF(6 + glowLevel * 14)
            }
            start()
        }
        if (target > 0.5f) {
            Ui.blip()
        }
    }
}
