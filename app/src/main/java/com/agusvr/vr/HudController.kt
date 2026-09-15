package com.agusvr.vr

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.agusvr.R
import com.agusvr.performance.PerfSample
import com.agusvr.runtime.RuntimeCore
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Ui
import com.agusvr.util.dp

/**
 * The heads-up layer of the VR world (module: AgusSpatialUI): discreet status
 * chips (camera · hand tracking · fps) and quick actions (home, hand toggle,
 * status window, exit). Lives above the windows but below the ray overlay.
 */
class HudController(
    private val layer: FrameLayout,
    private val onHome: () -> Unit,
    private val onToggleHand: () -> Unit,
    private val onStatus: () -> Unit,
    private val onExit: () -> Unit
) {

    interface HandToggleState {
        fun handEnabled(): Boolean
    }

    var handStateProvider: HandToggleState? = null

    private val context: Context = layer.context
    private val camChip: TextView
    private val handChip: TextView
    private val fpsChip: TextView
    private val hint: TextView
    private val handButton: View

    init {
        // ---- top-left chips ----
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        camChip = chip("câmera …")
        handChip = chip("mãos …")
        fpsChip = chip("-- fps")
        chips.addView(camChip)
        chips.addView(handChip, chipLp())
        chips.addView(fpsChip, chipLp())
        layer.addView(chips, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
        ).apply { setMargins(context.dp(14), context.dp(12), 0, 0) })

        // ---- top-right actions ----
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(AgusWidgets.iconButton(context, R.drawable.ic_home, "Início") { onHome() })
        handButton = AgusWidgets.iconButton(context, R.drawable.ic_handlab, "Rastreamento de mãos") { onToggleHand() }
        actions.addView(handButton, actionLp())
        actions.addView(AgusWidgets.iconButton(context, R.drawable.ic_status, "Status do sistema") { onStatus() }, actionLp())
        actions.addView(AgusWidgets.iconButton(context, R.drawable.ic_exit, "Sair do VR") { onExit() }, actionLp())
        layer.addView(actions, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        ).apply { setMargins(0, context.dp(10), context.dp(12), 0) })

        // ---- bottom hint ----
        hint = AgusWidgets.monoText(context, "pinça = selecionar · punho = arrastar · dedo indicador = apontar", AgusWidgets.FAINT, 8.5f)
        hint.gravity = Gravity.CENTER
        layer.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { setMargins(0, 0, 0, context.dp(10)) })
    }

    fun setHint(text: String) {
        hint.text = text
    }

    /** Called ~4 Hz from the VR frame loop. */
    fun update(sample: PerfSample) {
        val camOk = RuntimeCore.cameraActive.value
        camChip.text = if (camOk) "câmera ativa" else if (RuntimeCore.useVirtualEnvironment()) "ambiente virtual" else "câmera off"
        camChip.setTextColor(if (camOk) AgusWidgets.GREEN else AgusWidgets.DIM)

        val st = RuntimeCore.handState.value
        handChip.text = "mãos: ${st.label}"
        handChip.setTextColor(
            when (st) {
                RuntimeCore.HandEngineState.RUNNING, RuntimeCore.HandEngineState.NO_HANDS -> AgusWidgets.CYAN
                RuntimeCore.HandEngineState.ERROR, RuntimeCore.HandEngineState.UNSUPPORTED -> Color.parseColor("#FF7A8A")
                else -> AgusWidgets.DIM
            }
        )
        fpsChip.text = "%.0f fps".format(sample.fps)
        fpsChip.setTextColor(if (sample.fps >= 45f) AgusWidgets.GREEN else if (sample.fps >= 28f) Color.parseColor("#FFC24D") else Color.parseColor("#FF7A8A"))

        handButton.alpha = if (handStateProvider?.handEnabled() == true) 1f else 0.4f
    }

    private fun chip(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 8.5f
        typeface = Ui.mono
        setTextColor(AgusWidgets.DIM)
        setPadding(context.dp(9), context.dp(5), context.dp(9), context.dp(5))
        background = GradientDrawable().apply {
            cornerRadius = context.dp(9).toFloat()
            setColor(Color.parseColor("#B00C1322"))
            setStroke(context.dp(1), Color.parseColor("#2E557CAA"))
        }
    }

    private fun chipLp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = context.dp(6) }

    private fun actionLp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = context.dp(2) }
}
