package com.agusvr.settings

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import com.agusvr.R
import com.agusvr.performance.PerformanceMonitor
import com.agusvr.performance.PerfMode
import com.agusvr.runtime.AgusBus
import com.agusvr.runtime.AgusEvent
import com.agusvr.ui.dialogs.AgusDialogs
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.dp
import com.agusvr.windows.DisposableView

/**
 * Spatial settings (module: AgusSettings). Every control is wired to a real
 * setting consumed by the runtime (distance, scale, sensitivity, dwell, ray,
 * hand tracking, effects, performance…). Changes apply live.
 */
class SettingsPanel(
    context: Context,
    private val onSettingsApplied: () -> Unit
) : LinearLayout(context), DisposableView {

    init {
        orientation = VERTICAL

        val content = LinearLayout(context).apply { orientation = VERTICAL }

        // ---------------- Interaction ----------------
        content.addView(AgusWidgets.sectionTitle(context, context.getString(R.string.set_group_interaction)))
        content.addView(AgusWidgets.sliderRow(context, context.getString(R.string.set_distance), 0.7f, 1.5f, SettingsRepo.uiDistance,
            { "%.2f×".format(it) }) { SettingsRepo.uiDistance = it; onSettingsApplied() })
        content.addView(AgusWidgets.sliderRow(context, context.getString(R.string.set_scale), 0.7f, 1.4f, SettingsRepo.uiScale,
            { "%.2f×".format(it) }) { SettingsRepo.uiScale = it; onSettingsApplied() })
        content.addView(AgusWidgets.sliderRow(context, context.getString(R.string.set_sensitivity), 0.5f, 2.0f, SettingsRepo.pointSensitivity,
            { "%.2f×".format(it) }) { SettingsRepo.pointSensitivity = it })
        content.addView(AgusWidgets.sliderRow(context, context.getString(R.string.set_select_dist), 0.5f, 2.0f, SettingsRepo.selectDistance,
            { "%.2f×".format(it) }) { SettingsRepo.selectDistance = it })
        content.addView(AgusWidgets.sliderRow(context, context.getString(R.string.set_dwell), 250f, 1500f, SettingsRepo.dwellMs.toFloat(),
            { "${it.toInt()} ms" }) { SettingsRepo.dwellMs = it.toInt() })
        content.addView(AgusWidgets.sliderRow(context, context.getString(R.string.set_ray_intensity), 0.2f, 1.0f, SettingsRepo.rayIntensity,
            { "${(it * 100).toInt()}%" }) { SettingsRepo.rayIntensity = it })
        content.addView(AgusWidgets.sliderRow(context, context.getString(R.string.set_ray_length), 0.4f, 1.8f, SettingsRepo.rayLength,
            { "%.2f×".format(it) }) { SettingsRepo.rayLength = it })

        content.addView(AgusWidgets.divider(context))

        // ---------------- Hand tracking ----------------
        content.addView(AgusWidgets.sectionTitle(context, context.getString(R.string.set_group_tracking)))
        content.addView(AgusWidgets.toggleRow(context, context.getString(R.string.set_hand_enabled), false) { on ->
            if (!com.agusvr.runtime.BuildFlags.HAND_TRACKING) {
                com.agusvr.runtime.AgusBus.toast("Rastreamento de mãos",
                    "Chega em uma atualização futura — esta build é focada na interface espacial 3d curva.",
                    R.drawable.ic_handlab, com.agusvr.runtime.AgusEvent.ToastKind.INFO)
                SettingsRepo.handEnabled = false
            } else {
                SettingsRepo.handEnabled = on
            }
            onSettingsApplied()
        })
        val delegateRow = LinearLayout(context).apply { orientation = VERTICAL }
        delegateRow.addView(AgusWidgets.bodyText(context, context.getString(R.string.set_hand_delegate), AgusWidgets.TEXT, 11.5f).apply {
            setPadding(0, context.dp(7), 0, context.dp(2))
        })
        val options = listOf("Auto", "GPU", "CPU")
        val initial = when (SettingsRepo.handDelegate) {
            "gpu" -> 1; "cpu" -> 2; else -> 0
        }
        delegateRow.addView(AgusWidgets.segmented(context, options, initial) { idx ->
            SettingsRepo.handDelegate = when (idx) { 1 -> "gpu"; 2 -> "cpu"; else -> "auto" }
            onSettingsApplied()
        })
        content.addView(delegateRow)

        content.addView(AgusWidgets.divider(context))

        // ---------------- Visual ----------------
        content.addView(AgusWidgets.sectionTitle(context, context.getString(R.string.set_group_visual)))
        content.addView(AgusWidgets.toggleRow(context, context.getString(R.string.set_effects), SettingsRepo.effects) {
            SettingsRepo.effects = it; PerformanceMonitor.publishProfile(); onSettingsApplied()
        })
        content.addView(AgusWidgets.toggleRow(context, context.getString(R.string.set_shadows), SettingsRepo.shadows) {
            SettingsRepo.shadows = it; onSettingsApplied()
        })
        content.addView(AgusWidgets.toggleRow(context, context.getString(R.string.set_particles), SettingsRepo.particles) {
            SettingsRepo.particles = it; PerformanceMonitor.publishProfile(); onSettingsApplied()
        })
        content.addView(AgusWidgets.toggleRow(context, context.getString(R.string.set_debug_overlay), SettingsRepo.debugOverlay) {
            SettingsRepo.debugOverlay = it
        })
        content.addView(AgusWidgets.toggleRow(context, context.getString(R.string.set_haptics), SettingsRepo.haptics) {
            SettingsRepo.haptics = it
            if (it) AgusBus.post(AgusEvent.Toast(R.drawable.ic_settings, "Vibração ativada", null))
        })
        content.addView(AgusWidgets.toggleRow(context, context.getString(R.string.set_audio), SettingsRepo.audio) {
            SettingsRepo.audio = it
            if (it) com.agusvr.util.Ui.blip()
        })

        content.addView(AgusWidgets.divider(context))

        // ---------------- System ----------------
        content.addView(AgusWidgets.sectionTitle(context, context.getString(R.string.set_group_system)))
        val modes = listOf(
            context.getString(R.string.perf_mode_performance),
            context.getString(R.string.perf_mode_balanced),
            context.getString(R.string.perf_mode_quality)
        )
        val modeIdx = PerfMode.fromKey(SettingsRepo.perfMode).ordinal
        content.addView(AgusWidgets.segmented(context, modes, modeIdx) { idx ->
            SettingsRepo.perfMode = PerfMode.entries[idx].key
            PerformanceMonitor.publishProfile()
            onSettingsApplied()
        })
        content.addView(AgusWidgets.toggleRow(context, context.getString(R.string.set_auto_quality), SettingsRepo.autoQuality) {
            SettingsRepo.autoQuality = it; PerformanceMonitor.publishProfile()
        })
        content.addView(AgusWidgets.toggleRow(context, context.getString(R.string.set_battery_saver), SettingsRepo.batterySaver) {
            SettingsRepo.batterySaver = it; PerformanceMonitor.publishProfile(); onSettingsApplied()
        })
        val camRow = LinearLayout(context).apply { orientation = VERTICAL }
        camRow.addView(AgusWidgets.bodyText(context, context.getString(R.string.set_camera_res), AgusWidgets.TEXT, 11.5f).apply {
            setPadding(0, context.dp(7), 0, context.dp(2))
        })
        val camOptions = listOf("Baixa", "Média", "Alta")
        val camIdx = when (SettingsRepo.cameraTier) { "low" -> 0; "high" -> 2; else -> 1 }
        camRow.addView(AgusWidgets.segmented(context, camOptions, camIdx) { idx ->
            SettingsRepo.cameraTier = when (idx) { 0 -> "low"; 2 -> "high"; else -> "medium" }
            onSettingsApplied()
        })
        content.addView(camRow)

        val homeRow = LinearLayout(context).apply { orientation = VERTICAL }
        homeRow.addView(AgusWidgets.bodyText(context, context.getString(R.string.set_browser_home), AgusWidgets.TEXT, 11.5f).apply {
            setPadding(0, context.dp(12), 0, context.dp(4))
        })
        homeRow.addView(AgusWidgets.ghostButton(context, SettingsRepo.browserHome, R.drawable.ic_link) {
            AgusDialogs.input(context, context.getString(R.string.set_browser_home), "https://…", SettingsRepo.browserHome) { v ->
                SettingsRepo.browserHome = v
                AgusBus.toast("Página inicial atualizada", v, R.drawable.ic_browser)
            }
        })
        content.addView(homeRow)

        content.addView(AgusWidgets.divider(context))
        val resetRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        resetRow.addView(AgusWidgets.ghostButton(context, context.getString(R.string.set_reset), R.drawable.ic_reload) {
            AgusDialogs.confirm(context, context.getString(R.string.set_reset),
                "Todas as configurações voltarão aos valores padrão.", "Restaurar") {
                SettingsRepo.resetDefaults()
                PerformanceMonitor.publishProfile()
                onSettingsApplied()
                AgusBus.toast("Configurações restauradas", null, R.drawable.ic_settings)
            }
        })
        resetRow.addView(AgusWidgets.ghostButton(context, "Exportar settings.json", R.drawable.ic_save) {
            val f = SettingsRepo.exportToFile()
            if (f != null) AgusBus.toast("Configurações exportadas", com.agusvr.storage.AgusPaths.pathLabel(f), R.drawable.ic_save)
            else AgusDialogs.info(context, "Exportar", "Não foi possível exportar as configurações.")
        }.also { (it.layoutParams as? LayoutParams)?.marginStart = context.dp(8) })
        content.addView(resetRow)

        val scroll = AgusWidgets.scrollContent(context, content)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun dispose() {}
}
