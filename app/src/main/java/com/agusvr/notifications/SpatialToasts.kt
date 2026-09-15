package com.agusvr.notifications

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.agusvr.R
import com.agusvr.runtime.AgusEvent
import com.agusvr.util.Logx
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.util.dpF

/**
 * Discreet spatial notifications floating at the bottom-right of the VR world
 * (module: AgusNotifications). They never cover the full screen: small glass
 * cards that slide in, hold for ~3 s and fade out, queued FIFO.
 */
class SpatialToasts(private val layer: FrameLayout) {

    private val context: Context get() = layer.context
    private val queue = ArrayDeque<Pending>()
    private var active = 0
    private val maxActive = 3

    private data class Pending(val iconRes: Int, val title: String, val message: String?, val kind: AgusEvent.ToastKind)

    fun show(iconRes: Int, title: String, message: String? = null, kind: AgusEvent.ToastKind = AgusEvent.ToastKind.INFO) {
        queue.addLast(Pending(iconRes, title, message, kind))
        pump()
    }

    private fun pump() {
        while (active < maxActive && queue.isNotEmpty()) {
            val p = queue.removeFirst()
            active++
            showCard(p)
        }
    }

    private fun showCard(p: Pending) {
        try {
            val card = buildCard(p)
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            )
            lp.setMargins(0, 0, context.dp(18), context.dp(14) + active * context.dp(74))
            card.layoutParams = lp
            card.alpha = 0f
            card.translationX = context.dpF(40)
            layer.addView(card)
            card.animate().alpha(1f).translationX(0f).setDuration(220).start()
            card.postDelayed({ dismiss(card) }, 3200)
        } catch (t: Throwable) {
            Logx.w("Toasts", "toast failed", t)
            active--
            pump()
        }
    }

    private fun dismiss(card: View) {
        card.animate().alpha(0f).translationX(context.dpF(40)).setDuration(240)
            .setListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        layer.removeView(card)
                        active--
                        pump()
                    }
                }
            }).start()
    }

    private fun buildCard(p: Pending): View {
        val accent = when (p.kind) {
            AgusEvent.ToastKind.SUCCESS -> Color.parseColor("#5EFFB1")
            AgusEvent.ToastKind.ERROR -> Color.parseColor("#FF5E7A")
            AgusEvent.ToastKind.INFO -> Color.parseColor("#4DE8FF")
        }
        val bg = GradientDrawable().apply {
            cornerRadius = context.dpF(16)
            setColor(Color.parseColor("#F20B1120"))
            setStroke(context.dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(context.dp(14), context.dp(10), context.dp(16), context.dp(10))
            elevation = context.dpF(8)
        }
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(22), context.dp(22)).apply {
                marginEnd = context.dp(10)
            }
            setImageResource(if (p.iconRes != 0) p.iconRes else R.drawable.ic_spark)
            setColorFilter(accent)
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(context).apply {
            text = p.title
            setTextColor(Color.parseColor("#F2F7FF"))
            textSize = 12.5f
            typeface = Ui.ui
            letterSpacing = 0.02f
        }
        texts.addView(title)
        if (!p.message.isNullOrEmpty()) {
            val msg = TextView(context).apply {
                text = p.message
                setTextColor(Color.parseColor("#B3A9B8D8"))
                textSize = 10.5f
                typeface = Ui.mono
                maxLines = 2
            }
            texts.addView(msg)
        }
        val accentBar = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(3), context.dp(30)).apply {
                marginEnd = context.dp(10)
            }
            background = GradientDrawable().apply {
                cornerRadius = context.dpF(2)
                setColor(accent)
            }
        }
        row.addView(accentBar)
        row.addView(icon)
        row.addView(texts)
        return row
    }

    fun clearAll() {
        queue.clear()
        for (i in layer.childCount - 1 downTo 0) layer.removeViewAt(i)
        active = 0
    }
}
