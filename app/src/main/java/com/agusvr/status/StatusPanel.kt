package com.agusvr.status

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.agusvr.R
import com.agusvr.performance.PerformanceMonitor
import com.agusvr.runtime.RuntimeCore
import com.agusvr.storage.AgusPaths
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.dirSize
import com.agusvr.util.dp
import com.agusvr.util.formatBytes
import com.agusvr.windows.DisposableView

/**
 * System status window (module: AgusVRRuntime diagnostics): device
 * capabilities, live subsystem state, storage usage of the AgusVR tree.
 */
class StatusPanel(context: Context) : LinearLayout(context), DisposableView {

    companion object {
        /** VRActivity installs this so the panel can report window statistics. */
        @Volatile
        var windowStatsProvider: (() -> String)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val body: TextView

    init {
        orientation = VERTICAL
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))

        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(AgusWidgets.panelTitle(context, context.getString(R.string.app_status)))
        header.addView(View(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
        header.addView(AgusWidgets.iconButton(context, R.drawable.ic_reload, "Atualizar") { render() })
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body = AgusWidgets.monoText(context, "coletando…", AgusWidgets.TEXT, 9.5f)
        val scroll = android.widget.ScrollView(context).apply {
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            overScrollMode = OVER_SCROLL_NEVER
        }
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(8) })

        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                handler.post(ticker)
            }

            override fun onViewDetachedFromWindow(v: View) {
                handler.removeCallbacks(ticker)
            }
        })
        render()
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1500)
        }
    }

    private fun render() {
        val s = PerformanceMonitor.sample.value
        val sb = StringBuilder()
        sb.append(RuntimeCore.describe()).append("\n\n")
        sb.append("── tempo real ──\n")
        sb.append("fps: %.1f · quadro: %.1f ms\n".format(s.fps, s.frameMs))
        sb.append("memória app: ${s.memUsedMb}/${s.memMaxMb} MB · heap nativo: ${PerformanceMonitor.nativeHeapMb()} MB\n")
        sb.append("bateria: ${if (s.batteryPct >= 0) "${s.batteryPct}%" else "?"}${if (s.batteryCharging) " (carregando)" else ""}")
        if (s.temperatureC > 0f) sb.append(" · temp: %.1f °C".format(s.temperatureC))
        sb.append('\n')
        sb.append("mãos: ${RuntimeCore.handState.value.label} · câmera: ${if (RuntimeCore.cameraActive.value) "ativa" else "inativa"}\n")
        sb.append("janelas: ${windowStatsProvider?.invoke() ?: "-"}\n\n")
        sb.append("── armazenamento AgusVR ──\n")
        for ((label, dir) in listOf(
            "Library" to AgusPaths.library, "Games" to AgusPaths.games, "Apps" to AgusPaths.apps,
            "Models" to AgusPaths.models, "Images" to AgusPaths.images, "Downloads" to AgusPaths.downloads,
            "Projects" to AgusPaths.projects, "Settings" to AgusPaths.settings
        )) {
            sb.append("%-10s %s\n".format(label, formatBytes(dirSize(dir))))
        }
        sb.append("livre no dispositivo: ${formatBytes(AgusPaths.freeSpaceBytes())}\n")
        sb.append("raiz: ${AgusPaths.pathLabel(AgusPaths.root)}")
        body.text = sb.toString()
    }

    override fun dispose() {
        handler.removeCallbacks(ticker)
    }
}
