package com.agusvr.performance

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.agusvr.R
import com.agusvr.runtime.RuntimeCore
import com.agusvr.settings.SettingsRepo
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.windows.DisposableView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Performance panel (module: AgusPerformance): live FPS graph, memory,
 * battery, temperature, CPU load, hand tracking state, mode selection
 * (Performance / Balanced / Quality) and the automatic scaler event log.
 */
class PerformancePanel(context: Context) : LinearLayout(context), DisposableView {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class FpsGraph(context: Context) : View(context) {
        private val history = FloatArray(90)
        private var idx = 0
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.parseColor("#4DE8FF")
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val grid = Paint().apply { color = Color.parseColor("#22557CAA"); strokeWidth = 1.5f }
        private val path = Path()

        fun push(fps: Float) {
            history[idx % history.size] = fps
            idx++
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            canvas.drawColor(Color.parseColor("#0C1322"))
            // grid lines at 30/60 fps
            for (target in listOf(30f, 60f)) {
                val y = h - (target / 75f) * h
                canvas.drawLine(0f, y, w, y, grid)
            }
            path.reset()
            val n = minOf(idx, history.size)
            if (n > 1) {
                for (i in 0 until n) {
                    val v = history[(idx - n + i + history.size * 2) % history.size]
                    val x = i * w / (history.size - 1)
                    val y = h - (v.coerceAtMost(75f) / 75f) * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                fill.color = Color.parseColor("#1F4DE8FF")
                val closed = Path(path)
                closed.lineTo((n - 1) * w / (history.size - 1), h)
                closed.lineTo(0f, h)
                closed.close()
                canvas.drawPath(closed, fill)
                canvas.drawPath(path, line)
            }
        }
    }

    private val graph = FpsGraph(context)
    private val fpsText: TextView
    private val memText: TextView
    private val battText: TextView
    private val tempText: TextView
    private val cpuText: TextView
    private val handText: TextView
    private val eventsBox: LinearLayout

    init {
        orientation = VERTICAL
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(8))

        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(AgusWidgets.panelTitle(context, context.getString(R.string.perf_title)))
        header.addView(View(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
        val modeIdx = PerfMode.fromKey(SettingsRepo.perfMode).ordinal
        header.addView(AgusWidgets.segmented(context, listOf(
            context.getString(R.string.perf_mode_performance),
            context.getString(R.string.perf_mode_balanced),
            context.getString(R.string.perf_mode_quality)
        ), modeIdx) { idx ->
            SettingsRepo.perfMode = PerfMode.entries[idx].key
            PerformanceMonitor.publishProfile()
            com.agusvr.runtime.AgusBus.post(com.agusvr.runtime.AgusEvent.SettingsChanged)
        })
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(graph, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(72)).apply {
            topMargin = context.dp(10)
        })

        fpsText = statLine("FPS", "—")
        memText = statLine(context.getString(R.string.perf_memory), "—")
        cpuText = statLine(context.getString(R.string.perf_cpu), "—")
        battText = statLine(context.getString(R.string.perf_battery), "—")
        tempText = statLine(context.getString(R.string.perf_temperature), "—")
        handText = statLine(context.getString(R.string.perf_hand_state), "—")

        addView(AgusWidgets.sectionTitle(context, context.getString(R.string.perf_auto_events)))
        eventsBox = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = android.widget.ScrollView(context).apply {
            addView(eventsBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            overScrollMode = OVER_SCROLL_NEVER
        }
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        scope.launch {
            PerformanceMonitor.sample.collectLatest { s ->
                fpsText.text = "%.1f fps · %.1f ms".format(s.fps, s.frameMs)
                memText.text = "app ${s.memUsedMb}/${s.memMaxMb} MB · nativo ${PerformanceMonitor.nativeHeapMb()} MB · sistema ${systemMem()}"
                cpuText.text = "%.0f%%".format(s.cpuAppPct)
                battText.text = if (s.batteryPct >= 0) "${s.batteryPct}%" + (if (s.batteryCharging) " · carregando" else "") else "indisponível"
                tempText.text = if (s.temperatureC > 0f) "%.1f °C".format(s.temperatureC) else "sensor indisponível"
                handText.text = "${s.handState} · auto-nível ${s.autoLevel}"
                graph.push(s.fps)
            }
        }
        scope.launch {
            PerformanceMonitor.autoEvents.collectLatest { list ->
                eventsBox.removeAllViews()
                if (list.isEmpty()) {
                    eventsBox.addView(AgusWidgets.monoText(context, "nenhum ajuste automático até agora"))
                } else {
                    for (e in list) {
                        eventsBox.addView(AgusWidgets.monoText(context, "· ${com.agusvr.util.formatDate(e.substringBefore(" · ").toLongOrNull() ?: 0L)} — ${e.substringAfter(" · ")}", AgusWidgets.DIM, 9f))
                    }
                }
            }
        }
        scope.launch {
            RuntimeCore.handState.collectLatest { st ->
                handText.text = "${st.label} · auto-nível ${PerformanceMonitor.sample.value.autoLevel}"
            }
        }
    }

    private fun systemMem(): String {
        val (avail, total) = PerformanceMonitor.systemMemoryInfo()
        return if (total > 0) "$avail/$total MB livres" else "—"
    }

    private fun statLine(label: String, value: String): TextView {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val l = AgusWidgets.bodyText(context, label, AgusWidgets.DIM, 10.5f)
        l.typeface = Ui.mono
        val v = TextView(context).apply {
            text = value
            setTextColor(AgusWidgets.CYAN)
            textSize = 10f
            typeface = Ui.mono
            gravity = Gravity.END
        }
        row.addView(l, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f))
        row.addView(v, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
        row.setPadding(0, context.dp(3), 0, context.dp(3))
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return v
    }

    override fun dispose() {
        scope.cancel()
    }
}
