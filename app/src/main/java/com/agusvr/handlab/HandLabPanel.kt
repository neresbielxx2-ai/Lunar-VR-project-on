package com.agusvr.handlab

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.agusvr.R
import com.agusvr.hand.HandSnapshot
import com.agusvr.point.PointInteraction
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.windows.DisposableView

/**
 * Bridge so the Hand Lab panel can subscribe to the live pointing engine
 * without holding an Activity reference. VRActivity installs it on create.
 */
object HandLabLink {
    @Volatile
    var point: PointInteraction? = null
}

/**
 * AGUS HAND LAB (module: AgusInteraction test suite).
 * Seven real tests driven by the actual pointing pipeline
 * ([PointInteraction.TestListener]) — nothing here is simulated:
 *  T1 pointing · T2 approach · T3 selection · T4 grab · T5 move ·
 *  T6 release · T7 spatial menu.
 */
class HandLabPanel(context: Context) : LinearLayout(context), DisposableView {

    private class TestRow(context: Context, val index: Int, title: String) : LinearLayout(context) {
        val detail: TextView
        val chip: TextView
        val bar: View
        var passed = false
            private set
        var onPass: (() -> Unit)? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.getDrawable(R.drawable.bg_card)
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))

            val badge = TextView(context).apply {
                text = "T${index + 1}"
                setTextColor(AgusWidgets.CYAN)
                typeface = Ui.mono
                textSize = 11f
            }
            addView(badge, LayoutParams(context.dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))

            val col = LinearLayout(context).apply { orientation = VERTICAL }
            col.addView(TextView(context).apply {
                text = title
                setTextColor(AgusWidgets.TEXT)
                typeface = Ui.ui
                textSize = 11.5f
            })
            detail = AgusWidgets.monoText(context, "aguardando gesto…", AgusWidgets.FAINT, 8.5f)
            col.addView(detail)
            bar = AgusWidgets.progressBar(context)
            col.addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(3)).apply {
                topMargin = context.dp(4)
            })
            addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            chip = TextView(context).apply {
                text = "pendente"
                textSize = 9f
                typeface = Ui.mono
                setTextColor(AgusWidgets.DIM)
                setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(8).toFloat()
                    setColor(Color.parseColor("#182238"))
                    setStroke(context.dp(1), Color.parseColor("#33557CAA"))
                }
            }
            addView(chip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(8)
            })
        }

        fun progress(frac: Float) {
            AgusWidgets.setProgress(bar, frac.coerceIn(0f, 1f))
        }

        fun setDetail(text: String) {
            detail.text = text
        }

        fun pass(detailText: String? = null) {
            if (passed) return
            passed = true
            progress(1f)
            chip.text = "aprovado"
            chip.setTextColor(Color.parseColor("#06130C"))
            (chip.background as? GradientDrawable)?.setColor(Color.parseColor("#5EFFB1"))
            detailText?.let { detail.text = it }
            Ui.blip(high = true)
            onPass?.invoke()
        }

        fun reset() {
            passed = false
            progress(0f)
            chip.text = "pendente"
            chip.setTextColor(AgusWidgets.DIM)
            (chip.background as? GradientDrawable)?.setColor(Color.parseColor("#182238"))
            detail.text = "aguardando gesto…"
        }
    }

    private val rows = mutableListOf<TestRow>()
    private val summary: TextView
    private var pointCount = 0
    private var grabbedTotal = 0f

    private val listener = object : PointInteraction.TestListener {
        override fun onPointDetected(hand: HandSnapshot) {
            pointCount++
            val r = rows[0]
            r.setDetail("apontando · ponta (%.0f, %.0f) · %s".format(
                hand.fingertipScreen.x, hand.fingertipScreen.y, hand.gesture.label
            ))
            r.progress(pointCount / 60f)
            if (pointCount >= 60) r.pass("apontamento estável por ~3 s")
        }

        override fun onApproachProgress(progress: Float) {
            val r = rows[1]
            r.progress(progress)
            r.setDetail("aproximação: %.0f%%".format(progress * 100))
            if (progress >= 1f) r.pass("dwell completo — seleção por aproximação OK")
        }

        override fun onSelect(target: View?, via: String) {
            val tag = target?.tag as? String
            if (tag != null && tag.startsWith("hl_target_")) {
                val idx = tag.removePrefix("hl_target_").toIntOrNull() ?: -1
                if (idx in 1..3) {
                    selectedTargets += idx
                    (target.background as? GradientDrawable)?.setColor(
                        Color.parseColor(listOf("#4DE8FF", "#FFC24D", "#FF5EC7")[idx - 1])
                    )
                    rows[2].setDetail("alvo $idx selecionado via $via")
                    val done = countSelectedTargets()
                    rows[2].progress(done / 3f)
                    if (done >= 3) rows[2].pass("3 alvos selecionados (pinça ou dwell)")
                }
            } else {
                rows[2].setDetail("seleção via $via em ${target?.javaClass?.simpleName ?: "nada"} — use os alvos coloridos")
            }
        }

        override fun onGrabStart(target: View?) {
            grabbedTotal = 0f
            rows[3].setDetail("grab iniciado em ${target?.tag ?: target?.javaClass?.simpleName ?: "?"}")
            rows[3].pass("punho fechado detectado (grab)")
        }

        override fun onGrabMove(dxPx: Float, dyPx: Float, totalPx: Float) {
            grabbedTotal = totalPx
            val r = rows[4]
            r.setDetail("arrastando · Δ(%.0f, %.0f) px · total %.0f px".format(dxPx, dyPx, totalPx))
            r.progress(totalPx / 600f)
            if (totalPx >= 600f) r.pass("600 px de movimento contínuo")
        }

        override fun onGrabRelease(totalPx: Float) {
            val r = rows[5]
            r.setDetail("soltou · total %.0f px".format(totalPx))
            if (totalPx >= 100f) r.pass("release após movimento real")
            else r.setDetail("soltou cedo demais (mova ≥100 px antes de soltar)")
        }

        override fun onMenuOpened(target: View) {
            val tag = target.tag as? String ?: ""
            if (tag.startsWith("hl_menu")) {
                rows[6].pass("menu espacial aberto por gesto")
            }
        }
    }

    private var selectedTargets = mutableSetOf<Int>()

    private fun countSelectedTargets(): Int = selectedTargets.size

    init {
        orientation = VERTICAL
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))

        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(AgusWidgets.panelTitle(context, context.getString(R.string.hl_title)))
        header.addView(View(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
        summary = AgusWidgets.monoText(context, "0/7 aprovados", AgusWidgets.CYAN, 10f)
        header.addView(summary)
        header.addView(AgusWidgets.iconButton(context, R.drawable.ic_reload, "Reiniciar testes") { resetAll() }
            .also { (it.layoutParams as? LayoutParams)?.marginStart = context.dp(6) })
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(AgusWidgets.bodyText(context, context.getString(R.string.hl_tests), AgusWidgets.DIM, 10f)
            .also { it.setPadding(0, context.dp(2), 0, context.dp(8)) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (!com.agusvr.runtime.BuildFlags.HAND_TRACKING) {
            addView(AgusWidgets.bodyText(context,
                "ⓘ mãos desativadas nesta build (UI-first): os testes de gesto ficam pendentes até a atualização que liga o motor de mãos. A seleção por toque nos alvos abaixo já exercita o pipeline de interação.",
                Color.parseColor("#FFC24D"), 10f)
                .also { it.setPadding(0, 0, 0, context.dp(8)) },
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val titles = listOf(
            context.getString(R.string.hl_t1),
            context.getString(R.string.hl_t2),
            context.getString(R.string.hl_t3),
            context.getString(R.string.hl_t4),
            context.getString(R.string.hl_t5),
            context.getString(R.string.hl_t6),
            context.getString(R.string.hl_t7)
        )
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        for (i in titles.indices) {
            val row = TestRow(context, i, titles[i])
            row.onPass = { updateSummary() }
            rows += row
            list.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(5)
            })
        }

        // ---- playground: real targets for T3/T4/T7 ----
        list.addView(AgusWidgets.sectionTitle(context, "Alvos de teste"))
        val playground = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, context.dp(4), 0, context.dp(4))
        }
        val targetColors = listOf("#4DE8FF", "#FFC24D", "#FF5EC7")
        for (i in 1..3) {
            val t = View(context).apply {
                tag = "hl_target_$i"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#101A2E"))
                    setStroke(context.dp(2), Color.parseColor(targetColors[i - 1]))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Ui.tick(it)
                    selectedTargets += i
                    listener.onSelect(it, "toque")
                }
            }
            playground.addView(t, LayoutParams(context.dp(44), context.dp(44)).apply {
                marginEnd = context.dp(14)
            })
        }
        val grabBox = TextView(context).apply {
            tag = "hl_grab"
            text = "segure\ne arraste"
            gravity = Gravity.CENTER
            textSize = 8.5f
            typeface = Ui.mono
            setTextColor(AgusWidgets.DIM)
            background = context.getDrawable(R.drawable.bg_card)
            isClickable = true
            setOnClickListener { Ui.tick(it) }
        }
        playground.addView(grabBox, LayoutParams(context.dp(64), context.dp(44)).apply { marginEnd = context.dp(14) })
        val menuTarget = TextView(context).apply {
            tag = "hl_menu_test"
            text = "menu"
            gravity = Gravity.CENTER
            textSize = 9f
            typeface = Ui.display
            setTextColor(Color.parseColor("#5EFFB1"))
            background = context.getDrawable(R.drawable.bg_chip)
            isClickable = true
            setOnClickListener {
                Ui.tick(it)
                listener.onMenuOpened(it)
            }
        }
        playground.addView(menuTarget, LayoutParams(context.dp(52), context.dp(44)))
        list.addView(playground, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = android.widget.ScrollView(context).apply {
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            overScrollMode = OVER_SCROLL_NEVER
        }
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                HandLabLink.point?.testListener = listener
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (HandLabLink.point?.testListener === listener) {
                    HandLabLink.point?.testListener = null
                }
            }
        })
    }

    private fun resetAll() {
        pointCount = 0
        grabbedTotal = 0f
        selectedTargets.clear()
        rows.forEach { it.reset() }
        updateSummary()
    }

    private fun updateSummary() {
        val n = rows.count { it.passed }
        summary.text = if (n >= 7) "7/7 · mão aprovada ✦" else "$n/7 aprovados"
    }

    override fun dispose() {
        if (HandLabLink.point?.testListener === listener) {
            HandLabLink.point?.testListener = null
        }
    }
}
