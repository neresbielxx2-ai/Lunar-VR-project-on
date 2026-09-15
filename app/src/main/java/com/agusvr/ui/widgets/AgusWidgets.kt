package com.agusvr.ui.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.agusvr.R
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.util.dpF

/**
 * Small factory of on-brand UI atoms used by every spatial panel — buttons,
 * chips, sliders, toggles, headers — so no screen looks like a stock Android
 * dialog (spec §7: custom identity everywhere).
 */
object AgusWidgets {

    val CYAN = Color.parseColor("#4DE8FF")
    val GREEN = Color.parseColor("#5EFFB1")
    val VIOLET = Color.parseColor("#9D6BFF")
    val TEXT = Color.parseColor("#F2F7FF")
    val DIM = Color.parseColor("#B3A9B8D8")
    val FAINT = Color.parseColor("#668FA3C8")
    val MINT = Color.parseColor("#5EFFB1")
    val RED = Color.parseColor("#FF5E7A")
    val AMBER = Color.parseColor("#FFC24D")

    fun primaryButton(context: Context, label: String, iconRes: Int = 0, onClick: (View) -> Unit): TextView {
        val tv = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#04121A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Ui.display
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.bg_button_primary)
            setPadding(context.dp(18), context.dp(11), context.dp(18), context.dp(11))
            isClickable = true
            isFocusable = true
            if (iconRes != 0) {
                val d = context.getDrawable(iconRes)?.mutate()
                d?.setTint(Color.parseColor("#04121A"))
                d?.setBounds(0, 0, context.dp(16), context.dp(16))
                setCompoundDrawables(d, null, null, null)
                compoundDrawablePadding = context.dp(8)
            }
            setOnClickListener { v ->
                Ui.tick(v); Ui.blip(high = true)
                onClick(v)
            }
        }
        return tv
    }

    fun ghostButton(context: Context, label: String, iconRes: Int = 0, onClick: (View) -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Ui.ui
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.bg_button_ghost)
            setPadding(context.dp(14), context.dp(9), context.dp(14), context.dp(9))
            isClickable = true
            isFocusable = true
            if (iconRes != 0) {
                val d = context.getDrawable(iconRes)?.mutate()
                d?.setTint(CYAN)
                d?.setBounds(0, 0, context.dp(15), context.dp(15))
                setCompoundDrawables(d, null, null, null)
                compoundDrawablePadding = context.dp(7)
            }
            setOnClickListener { v ->
                Ui.tick(v); Ui.blip()
                onClick(v)
            }
        }
    }

    fun iconButton(context: Context, iconRes: Int, desc: String, tint: Int = DIM, onClick: (View) -> Unit): ImageView {
        return ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
            contentDescription = desc
            background = context.getDrawable(R.drawable.bg_button_ghost)
            setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
            layoutParams = LinearLayout.LayoutParams(context.dp(36), context.dp(36))
            isClickable = true
            isFocusable = true
            setOnClickListener { v ->
                Ui.tick(v); Ui.blip()
                onClick(v)
            }
        }
    }

    fun sectionTitle(context: Context, text: String, accent: Int = CYAN): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Ui.mono
            letterSpacing = 0.32f
            setPadding(0, context.dp(10), 0, context.dp(5))
        }

    fun panelTitle(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Ui.display
            letterSpacing = 0.22f
        }

    fun bodyText(context: Context, text: String, color: Int = DIM, size: Float = 12f): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = Ui.ui
            setLineSpacing(0f, 1.15f)
        }

    fun monoText(context: Context, text: String, color: Int = FAINT, size: Float = 9.5f): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = Ui.mono
            letterSpacing = 0.06f
        }

    fun chip(context: Context, label: String, selected: Boolean = false, onClick: (View) -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(if (selected) CYAN else DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Ui.ui
            gravity = Gravity.CENTER
            isSelected = selected
            background = context.getDrawable(R.drawable.bg_chip)
            setPadding(context.dp(13), context.dp(7), context.dp(13), context.dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { v -> Ui.tick(v); onClick(v) }
        }

    fun input(context: Context, hint: String, initial: String = "", singleLine: Boolean = true): EditText =
        EditText(context).apply {
            this.hint = hint
            setText(initial)
            setTextColor(TEXT)
            setHintTextColor(FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Ui.ui
            background = context.getDrawable(R.drawable.bg_input)
            setPadding(context.dp(13), context.dp(11), context.dp(13), context.dp(11))
            if (singleLine) {
                isSingleLine = true
                imeOptions = EditorDoneAction()
            }
            inputType = InputType.TYPE_CLASS_TEXT
        }

    private fun EditorDoneAction(): Int = android.view.inputmethod.EditorInfo.IME_ACTION_DONE

    /** Custom Agus slider row: label + value + seek bar (no stock Material look). */
    fun sliderRow(
        context: Context,
        label: String,
        min: Float, max: Float, value: Float,
        valueFmt: (Float) -> String,
        onChange: (Float) -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val name = bodyText(context, label, TEXT, 11.5f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueTv = monoText(context, valueFmt(value), CYAN, 10f)
        head.addView(name)
        head.addView(valueTv)
        val bar = SeekBar(context).apply {
            this.max = 1000
            progress = (((value - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000)
            progressDrawable = context.getDrawable(R.drawable.progress_agus)
            thumb = makeThumb(context)
            setPadding(context.dp(2), context.dp(8), context.dp(2), context.dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + (max - min) * (p / 1000f)
                    valueTv.text = valueFmt(v)
                    onChange(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        row.addView(head)
        row.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun makeThumb(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(CYAN)
        setStroke(context.dp(2), Color.parseColor("#FF04060C"))
        setSize(context.dp(16), context.dp(16))
    }

    /** Custom toggle row. */
    fun toggleRow(context: Context, label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(7), 0, context.dp(7))
            isClickable = true
            isFocusable = true
        }
        val name = bodyText(context, label, TEXT, 11.5f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val track = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(22))
        }
        val knob = View(context)
        val container = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(22))
            addView(track, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(knob, FrameLayout.LayoutParams(context.dp(16), context.dp(16), Gravity.CENTER_VERTICAL).apply {
                marginStart = context.dp(3)
            })
        }
        var on = initial

        fun render() {
            track.background = GradientDrawable().apply {
                cornerRadius = context.dpF(11)
                setColor(if (on) Color.parseColor("#404DE8FF") else Color.parseColor("#22304A78"))
                setStroke(context.dp(1), if (on) CYAN else Color.parseColor("#3D5A7BB8"))
            }
            (knob.layoutParams as FrameLayout.LayoutParams).gravity =
                Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)
            (knob.layoutParams as FrameLayout.LayoutParams).setMargins(context.dp(3), 0, context.dp(3), 0)
            knob.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) CYAN else Color.parseColor("#8FA3C8"))
            }
            knob.animate().translationX(if (on) context.dpF(22) else 0f).setDuration(140).start()
        }
        render()
        row.setOnClickListener {
            on = !on
            render()
            Ui.tick(row); Ui.blip()
            onChange(on)
        }
        row.addView(name)
        row.addView(container)
        return row
    }

    /** Segmented control (used by performance modes, Model Lab tools…). */
    fun segmented(context: Context, options: List<String>, initialIndex: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = context.dpF(12)
                setColor(Color.parseColor("#141A2C4E"))
                setStroke(context.dp(1), Color.parseColor("#2E557CAA"))
            }
            setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
        }
        val buttons = mutableListOf<TextView>()
        for ((i, opt) in options.withIndex()) {
            val b = TextView(context).apply {
                text = opt
                gravity = Gravity.CENTER
                setTextColor(DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Ui.ui
                setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
            }
            buttons += b
            row.addView(b)
        }
        fun select(idx: Int) {
            for ((i, b) in buttons.withIndex()) {
                if (i == idx) {
                    b.setTextColor(Color.parseColor("#04121A"))
                    b.typeface = Ui.display
                    b.background = GradientDrawable().apply {
                        cornerRadius = context.dpF(10)
                        colors = intArrayOf(CYAN, VIOLET)
                        orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    }
                } else {
                    b.setTextColor(DIM)
                    b.typeface = Ui.ui
                    b.background = null
                }
            }
        }
        for ((i, b) in buttons.withIndex()) {
            b.setOnClickListener {
                select(i)
                Ui.tick(b); Ui.blip()
                onPick(i)
            }
        }
        select(initialIndex.coerceIn(0, options.size - 1))
        return row
    }

    fun divider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)).apply {
            topMargin = context.dp(8); bottomMargin = context.dp(4)
        }
        setBackgroundColor(Color.parseColor("#1A557CAA"))
    }

    fun progressBar(context: Context): View {
        val track = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(6))
            background = context.getDrawable(R.drawable.bg_progress_track)
        }
        val fill = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
            background = context.getDrawable(R.drawable.bg_progress_fill)
            tag = "fill"
        }
        track.addView(fill)
        return track
    }

    fun setProgress(bar: View, frac: Float) {
        val fill = bar.findViewWithTag<View>("fill") ?: return
        val parent = fill.parent as View
        val w = parent.width
        fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (w > 0) (w * frac.coerceIn(0f, 1f)).toInt() else 0
        }
        fill.requestLayout()
    }

    fun emptyState(context: Context, iconRes: Int, text: String): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(24), context.dp(30), context.dp(24), context.dp(30))
        }
        box.addView(ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.parseColor("#337DE7FF"))
            layoutParams = LinearLayout.LayoutParams(context.dp(52), context.dp(52)).apply { bottomMargin = context.dp(12) }
        })
        box.addView(TextView(context).apply {
            this.text = text
            setTextColor(FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Ui.ui
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        })
        return box
    }

    fun scrollContent(context: Context, content: View): android.widget.ScrollView =
        android.widget.ScrollView(context).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isFillViewport = true
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(14))
            clipToPadding = false
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            verticalScrollbarThumbDrawable = context.getDrawable(R.drawable.bg_scroll_thumb)
        }
}
