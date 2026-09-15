package com.agusvr.windows

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.agusvr.R
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.util.dpF

/**
 * Chrome of a floating VR window (module: AgusWindowSystem): glass panel with
 * a title bar (drag handle + minimize/close), a content container and a
 * resize grip. Windows are real Views placed in 3D by [SpatialMath], so they
 * stay in space while the user looks around and can be grabbed with touch OR
 * with the hand (the synthesized grab stream drags the title bar).
 */
class WindowFrameView(
    context: Context,
    val appId: String,
    iconRes: Int,
    title: String,
    accent: Int
) : LinearLayout(context) {

    interface WindowCallbacks {
        fun onDragDelta(view: WindowFrameView, dxPx: Float, dyPx: Float)
        fun onResizeDelta(view: WindowFrameView, dxPx: Float, dyPx: Float)
        fun onClose(view: WindowFrameView)
        fun onFocus(view: WindowFrameView)
        fun onMinimize(view: WindowFrameView)
    }

    var callbacks: WindowCallbacks? = null

    val titleBar: LinearLayout
    val contentContainer: FrameLayout
    private val titleText: TextView
    private val border: GradientDrawable
    private val resizeGrip: View
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        orientation = VERTICAL
        background = context.getDrawable(R.drawable.bg_glass_panel)
        clipToOutline = false
        val d = resources.displayMetrics.density

        border = GradientDrawable().apply {
            cornerRadius = dpF(24)
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }

        // ---------------- title bar ----------------
        titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.getDrawable(R.drawable.bg_title_bar)
            setPadding(dp(14), dp(9), dp(8), dp(9))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(42))
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(9) }
            setImageResource(iconRes)
            setColorFilter(accent)
        }
        titleText = TextView(context).apply {
            text = title
            setTextColor(Color.parseColor("#F2F7FF"))
            textSize = 13f
            typeface = Ui.display
            letterSpacing = 0.14f
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        }
        val minimizeBtn = makeTitleButton(R.drawable.ic_minimize, "minimizar") {
            Ui.tick(it as View); Ui.blip()
            callbacks?.onMinimize(this)
        }
        val closeBtn = makeTitleButton(R.drawable.ic_close, "fechar") {
            Ui.tick(it as View); Ui.blip(high = true)
            callbacks?.onClose(this)
        }
        titleBar.addView(icon)
        titleBar.addView(titleText)
        titleBar.addView(minimizeBtn)
        titleBar.addView(closeBtn)

        // ---------------- content ----------------
        contentContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            background = context.getDrawable(R.drawable.bg_content)
            clipChildren = true
        }

        // ---------------- resize grip ----------------
        val bottom = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(16))
        }
        resizeGrip = ImageView(context).apply {
            setImageResource(R.drawable.ic_grip)
            setColorFilter(Color.parseColor("#668FA3C8"))
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(14), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(8)
            }
            rotation = 90f
            isClickable = true
        }
        bottom.addView(resizeGrip)

        addView(titleBar)
        addView(contentContainer)
        addView(bottom)

        elevation = dpF(14)
        outlineProvider = null

        setupDrag()
    }

    fun setTitle(title: String) {
        titleText.text = title
    }

    private fun makeTitleButton(iconRes: Int, desc: String, onClick: (View) -> Unit): View {
        val iv = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.parseColor("#B3A9B8D8"))
            background = context.getDrawable(R.drawable.bg_button_ghost)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(5) }
            isClickable = true
            isFocusable = true
            contentDescription = desc
            setOnClickListener { v -> onClick(v) }
        }
        return iv
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        titleBar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = e.rawX; lastTouchY = e.rawY
                    callbacks?.onFocus(this)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lastTouchX
                    val dy = e.rawY - lastTouchY
                    lastTouchX = e.rawX; lastTouchY = e.rawY
                    callbacks?.onDragDelta(this, dx, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        resizeGrip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = e.rawX; lastTouchY = e.rawY
                    callbacks?.onFocus(this)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lastTouchX
                    val dy = e.rawY - lastTouchY
                    lastTouchX = e.rawX; lastTouchY = e.rawY
                    callbacks?.onResizeDelta(this, dx, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    fun addContentView(v: View) {
        contentContainer.addView(
            v, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    fun clearContent() {
        contentContainer.removeAllViews()
    }
}
