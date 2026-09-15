package com.agusvr.vr3d

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.agusvr.R
import com.agusvr.browser.BrowserPanel
import com.agusvr.files.FilesPanel
import com.agusvr.handlab.HandLabPanel
import com.agusvr.library.LibraryPanel
import com.agusvr.modellab.ModelLabPanel
import com.agusvr.performance.PerformanceMonitor
import com.agusvr.performance.QualityProfile
import com.agusvr.runtime.AgusBus
import com.agusvr.runtime.AgusEvent
import com.agusvr.runtime.BuildFlags
import com.agusvr.settings.SettingsPanel
import com.agusvr.settings.SettingsRepo
import com.agusvr.status.StatusPanel
import com.agusvr.store.StorePanel
import com.agusvr.ui.PanelHost
import com.agusvr.ui.Panels
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.ui.viewers.ImageViewerPanel
import com.agusvr.ui.viewers.TextViewerPanel
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.windows.DisposableView
import com.agusvr.windows.VrApp

/**
 * AGUS spatial menus — 3DOF-only floating menu system (module: AgusSpatialUI/3D).
 *
 * World graph:
 *   WorldRoot (identity)
 *   ├── CameraRig → Camera (mono | SBS left/right)   ← gyroscope quaternion only
 *   ├── InitialBar (INICIAR · CONFIGURAÇÕES · SAIR)
 *   ├── MainMenu / SettingsMenu / SystemMenu
 *   └── FloatingPanels (apps reais como painéis 3D)
 *
 * Menus are children of the WORLD ROOT, never of the camera: head rotation
 * moves only the CameraRig, so every panel keeps its virtual transform
 * (position + quaternion + scale) and stays put while you look around.
 * No 6DOF, no SLAM, no ARCore, no environment/camera positioning.
 */
class Spatial3dActivity : AppCompatActivity(), PanelHost {

    private lateinit var root: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var contentHost: FrameLayout
    private lateinit var hint: TextView
    private lateinit var renderer: Spatial3dRenderer

    private var pxPerMeter = 1024f
    private var qualityCols = 10
    private var shadowsOn = true

    // touch state (mono)
    private var activeNode: SpatialPanelNode? = null
    private var draggingNode: SpatialPanelNode? = null
    private var lastX = 0f
    private var lastY = 0f

    private var pendingPick: ((Uri?) -> Unit)? = null
    private var pendingPerm: ((Boolean) -> Unit)? = null

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingPick?.invoke(uri); pendingPick = null
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        pendingPerm?.invoke(ok); pendingPerm = null
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#04070E"))

        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        contentHost = FrameLayout(this).apply { visibility = View.INVISIBLE }
        root.addView(contentHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        hint = TextView(this).apply {
            textSize = 9f
            typeface = Ui.mono
            setTextColor(Color.parseColor("#668FA3C8"))
            gravity = Gravity.CENTER
            text = "3DOF · os menus ficam no mundo virtual enquanto você olha ao redor · toque para interagir"
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })

        setContentView(root)
        root.keepScreenOn = true

        when (SettingsRepo.perfMode) {
            "performance" -> { qualityCols = 6; shadowsOn = false; pxPerMeter = 720f }
            "quality" -> { qualityCols = 10; shadowsOn = true; pxPerMeter = 1280f }
            else -> { qualityCols = 8; shadowsOn = true; pxPerMeter = 1024f }
        }

        renderer = Spatial3dRenderer(textureView, HeadTracker3Dof(this), qualityCols, shadowsOn)
        renderer.mode = if (SettingsRepo.sbsMode) Spatial3dRenderer.Mode.SBS else Spatial3dRenderer.Mode.MONO
        textureView.surfaceTextureListener = renderer.surfaceListener
        renderer.init()
        renderer.onGazeClick = { node, px -> tapAt(node, px) }
        Panels.host = this

        updateHint()
        textureView.setOnTouchListener(touchListener)
        openInitialBar()
    }

    override fun onResume() {
        super.onResume()
        val controller = WindowInsetsControllerCompat(window, root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        renderer.startLoop()
    }

    override fun onPause() {
        renderer.stopLoop()
        super.onPause()
    }

    override fun onDestroy() {
        Panels.host = null
        renderer.destroy()
        contentHost.removeAllViews()
        super.onDestroy()
    }

    private fun updateHint() {
        hint.text = if (renderer.mode == Spatial3dRenderer.Mode.SBS) {
            "SBS/VR Box · IPD ${SettingsRepo.ipdMm.toInt()} mm · olhar fixo (~1,2 s) seleciona"
        } else {
            "3DOF · os menus ficam no mundo virtual enquanto você olha ao redor · toque para interagir"
        }
    }

    // ------------------------------------------------------------------
    // Panel creation
    // ------------------------------------------------------------------

    private fun openPanel(
        id: String, content: View, wM: Float, hM: Float,
        dragFrac: Float = 0f, elevationDeg: Float = -2f
    ): SpatialPanelNode? {
        val e = renderer.engineOrNull() ?: return null
        val mats = renderer.materials.value ?: return null
        renderer.panel(id)?.let { old ->
            old.close()
        }
        val cw = (wM * pxPerMeter).toInt().coerceAtLeast(64)
        val ch = (hM * pxPerMeter).toInt().coerceAtLeast(64)
        contentHost.addView(content, FrameLayout.LayoutParams(cw, ch))
        val bridge = PanelTextureBridge(content, cw, ch, e)
        val node = SpatialPanelNode(e, mats, bridge, wM, hM, qualityCols, shadowsOn)
        node.dragRegionPx = ch * dragFrac
        node.onClosed = {
            (content as? DisposableView)?.dispose()
            contentHost.removeView(content)
        }
        val look = renderer.tracker.lookAngles()
        node.placeInFront(look[0] * 0.85f, (look[1] * 0.7f).coerceIn(-18f, 14f) + elevationDeg,
            (SettingsRepo.uiDistance * 1.7f).coerceIn(1.4f, 2.1f))
        bridge.markDirty()
        renderer.addPanel(id, node)
        return node
    }

    private fun closePanel(id: String) {
        renderer.panel(id)?.close()
    }

    // ---------------- initial bar ----------------

    private fun openInitialBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBg(20, 0x2AFFFFFF.toInt(), 0x33FFFFFF.toInt())
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        bar.addView(TextView(this).apply {
            text = "AGUS"
            typeface = Ui.display
            textSize = 15f
            letterSpacing = 0.34f
            setTextColor(Color.parseColor("#F2F7FF"))
        })
        bar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginStart = dp(16); marginEnd = dp(16) }
        })
        bar.addView(menuButton("INICIAR", accent = true) {
            Ui.blip()
            closePanel(BAR)
            openMainMenu()
        })
        bar.addView(sp(menuButton("CONFIGURAÇÕES") {
            Ui.blip()
            closePanel(BAR)
            openSettingsMenu()
        }))
        bar.addView(sp(menuButton("SAIR") {
            Ui.blip(high = true)
            finish()
        }))
        openPanel(BAR, bar, 1.02f, 0.155f, dragFrac = 1f)
    }

    // ---------------- main menu ----------------

    private fun openMainMenu() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBg(26, 0x24FFFFFF.toInt(), 0x2EFFFFFF.toInt())
        }
        col.addView(titleBar("Menus espaciais", onSystem = { openSystemMenu() }, onClose = { closePanel(MAIN) }))
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        val apps = listOf(VrApp.STORE, VrApp.LIBRARY, VrApp.BROWSER, VrApp.FILES,
            VrApp.SETTINGS, VrApp.PERFORMANCE, VrApp.MODELLAB, VrApp.HANDLAB)
        for (r in 0 until 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 4) {
                val app = apps[r * 4 + c]
                row.addView(tile(app), LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = if (c < 3) dp(8) else 0 })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = if (r == 0) dp(8) else 0 })
        }
        col.addView(grid)
        openPanel(MAIN, col, 1.16f, 0.46f, dragFrac = 0.12f)
    }

    private fun tile(app: VrApp): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = glassBg(16, 0x14FFFFFF.toInt(), 0x26FFFFFF.toInt())
            setPadding(dp(8), dp(12), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Ui.blip()
                openAppPanel(app)
            }
        }
        t.addView(ImageView(this).apply {
            setImageResource(app.iconRes)
            setColorFilter(Color.parseColor(app.accentHex))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        })
        t.addView(TextView(this).apply {
            text = getString(app.titleRes)
            textSize = 9.5f
            typeface = Ui.ui
            setTextColor(Color.parseColor("#DCE6F5"))
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(6), 0, 0)
        })
        return t
    }

    // ---------------- settings menu ----------------

    private fun openSettingsMenu() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBg(26, 0x24FFFFFF.toInt(), 0x2EFFFFFF.toInt())
        }
        col.addView(titleBar("Configurações", onSystem = null, onClose = { closePanel(SETTINGS) }))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }
        body.addView(AgusWidgets.toggleRow(this, "Modo SBS / VR Box (estéreo)", SettingsRepo.sbsMode) { on ->
            SettingsRepo.sbsMode = on
            renderer.mode = if (on) Spatial3dRenderer.Mode.SBS else Spatial3dRenderer.Mode.MONO
            updateHint()
        })
        body.addView(AgusWidgets.sliderRow(this, "IPD (mm)", 52f, 76f, SettingsRepo.ipdMm,
            { "%.0f mm".format(it) }) { SettingsRepo.ipdMm = it })
        body.addView(AgusWidgets.sliderRow(this, "Distância da interface", 0.7f, 1.5f, SettingsRepo.uiDistance,
            { "%.2f×".format(it) }) { v ->
            SettingsRepo.uiDistance = v
            for (n in renderer.allPanels()) {
                n.distance = (v * 1.7f).coerceIn(1.4f, 2.1f)
                n.updateTransform()
            }
        })
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in listOf("performance" to "Desempenho", "balanced" to "Equilibrado", "quality" to "Qualidade")) {
            val active = SettingsRepo.perfMode == m.first
            modes.addView(TextView(this).apply {
                text = m.second
                textSize = 10f
                typeface = Ui.ui
                gravity = Gravity.CENTER
                setTextColor(if (active) Color.parseColor("#06130C") else Color.parseColor("#B9C6DA"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (active) Color.parseColor("#7FE3C4") else 0x14FFFFFF.toInt())
                }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                setOnClickListener {
                    SettingsRepo.perfMode = m.first
                    AgusBus.toast("Qualidade", "Aplicado ao reabrir o modo espacial: ${m.second}",
                        R.drawable.ic_performance, AgusEvent.ToastKind.INFO)
                    closePanel(SETTINGS)
                    openSettingsMenu()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) })
        }
        body.addView(modes, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        body.addView(AgusWidgets.bodyText(this,
            if (BuildFlags.HAND_TRACKING) "Mãos: ativas nesta build."
            else "Mãos: chegam em atualização futura (build UI-first) — interação por toque/olhar.",
            Color.parseColor("#8FA3C8"), 9.5f).apply { setPadding(0, dp(8), 0, 0) })
        body.addView(menuButton("RECENTRALIZAR VISÃO") {
            renderer.tracker.recenter()
            Ui.blip()
        }.apply { layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) } })
        col.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        openPanel(SETTINGS, col, 0.98f, 0.56f, dragFrac = 0.09f)
    }

    // ---------------- system menu ----------------

    private fun openSystemMenu() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBg(26, 0x24FFFFFF.toInt(), 0x2EFFFFFF.toInt())
        }
        col.addView(titleBar("Sistema", onSystem = null, onClose = { closePanel(SYSTEM) }))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }
        val s = PerformanceMonitor.sample.value
        body.addView(AgusWidgets.monoText(this,
            "fps ${s.fps.toInt()} · mem ${s.memUsedMb} MB · bateria ${s.batteryPct}%\n" +
                "modo 3DOF puro · sem SLAM/ARCore · painéis ${renderer.allPanels().size}",
            AgusWidgets.FAINT, 9f))
        body.addView(sp(menuButton("RECENTRALIZAR VISÃO") { renderer.tracker.recenter(); Ui.blip() }))
        body.addView(sp(menuButton("VOLTAR AO INÍCIO") {
            Ui.blip()
            for (id in listOf(MAIN, SETTINGS, SYSTEM)) closePanel(id)
            for (n in renderer.allPanels().filter { it !== renderer.panel(BAR) }) n.close()
            openInitialBar()
        }))
        body.addView(sp(menuButton("SAIR DO MODO ESPACIAL") { Ui.blip(high = true); finish() }))
        col.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        openPanel(SYSTEM, col, 0.86f, 0.42f, dragFrac = 0.11f)
    }

    // ---------------- app panels (real content, same constructors as VR) ----

    private fun openAppPanel(app: VrApp, param: String? = null) {
        val content: View = when (app) {
            VrApp.STORE -> StorePanel(this)
            VrApp.LIBRARY -> LibraryPanel(this)
            VrApp.BROWSER -> BrowserPanel(this, param)
            VrApp.FILES -> FilesPanel(this)
            VrApp.SETTINGS -> SettingsPanel(this) { }
            VrApp.PERFORMANCE -> com.agusvr.performance.PerformancePanel(this)
            VrApp.HANDLAB -> HandLabPanel(this)
            VrApp.MODELLAB -> ModelLabPanel(this, param)
            VrApp.STATUS -> StatusPanel(this)
            VrApp.GAME -> com.agusvr.library.GamePlayerPanel(this, param)
            VrApp.TEXTVIEW -> TextViewerPanel(this, param)
            VrApp.IMAGEVIEW -> ImageViewerPanel(this, param)
        }
        closePanel(MAIN)
        openPanel("app_${app.appId}", content, 1.22f, 0.74f, dragFrac = 0.075f)
    }

    // ------------------------------------------------------------------
    // Visual helpers
    // ------------------------------------------------------------------

    private fun glassBg(radiusDp: Int, fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun sp(v: View): View {
        (v.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(10)
        if (v.layoutParams == null) v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) }
        return v
    }

    private fun menuButton(label: String, accent: Boolean = false, onClick: () -> Unit): View {
        val b = TextView(this).apply {
            text = label
            typeface = Ui.display
            textSize = 11.5f
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            setTextColor(if (accent) Color.parseColor("#04120C") else Color.parseColor("#E7EEF9"))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (accent) Color.parseColor("#7FE3C4") else 0x16FFFFFF.toInt())
                if (!accent) setStroke(dp(1), 0x2AFFFFFF.toInt())
            }
            setPadding(dp(18), dp(11), dp(18), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        return b
    }

    private fun titleBar(title: String, onSystem: (() -> Unit)?, onClose: () -> Unit): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(26).toFloat(), dp(26).toFloat(),
                    dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f)
                setColor(0x1FFFFFFF.toInt())
            }
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        bar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(9) }
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat(); setColor(Color.parseColor("#7FE3C4"))
            }
        })
        bar.addView(TextView(this).apply {
            text = title
            typeface = Ui.display
            textSize = 11f
            letterSpacing = 0.14f
            setTextColor(Color.parseColor("#E7EEF9"))
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (onSystem != null) {
            bar.addView(AgusWidgets.iconButton(this, R.drawable.ic_info, "sistema") {
                Ui.blip(); onSystem()
            })
        }
        bar.addView(AgusWidgets.iconButton(this, R.drawable.ic_close, "fechar") {
            Ui.blip(high = true); onClose()
        })
        return bar
    }

    // ------------------------------------------------------------------
    // Touch → 3D picking (mono); gaze dwell handles SBS
    // ------------------------------------------------------------------

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private val touchListener = View.OnTouchListener { _, ev ->
        if (renderer.mode == Spatial3dRenderer.Mode.SBS) return@OnTouchListener false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = renderer.pickPanel(ev.x, ev.y)
                if (hit == null) {
                    activeNode = null; draggingNode = null
                    false
                } else {
                    val (node, px) = hit
                    lastX = ev.x; lastY = ev.y
                    if (px[1] < node.dragRegionPx) {
                        draggingNode = node
                        true
                    } else {
                        activeNode = node
                        node.bridge.dispatchTouch(px[0], px[1], MotionEvent.ACTION_DOWN)
                        true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val d = draggingNode
                if (d != null) {
                    val sens = SettingsRepo.pointSensitivity
                    d.azimuth = (d.azimuth + (ev.x - lastX) * 0.055f * sens).coerceIn(-62f, 62f)
                    d.elevation = (d.elevation - (ev.y - lastY) * 0.045f * sens).coerceIn(-34f, 34f)
                    d.updateTransform()
                    lastX = ev.x; lastY = ev.y
                    true
                } else {
                    val n = activeNode
                    if (n != null) {
                        val hit = renderer.pickPanel(ev.x, ev.y)
                        val px = if (hit != null && hit.first === n) hit.second else null
                        if (px != null) n.bridge.dispatchTouch(px[0], px[1], MotionEvent.ACTION_MOVE)
                        true
                    } else false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val n = activeNode
                if (n != null) {
                    val hit = renderer.pickPanel(ev.x, ev.y)
                    val px = if (hit != null && hit.first === n) hit.second else null
                    if (px != null) n.bridge.dispatchTouch(px[0], px[1], MotionEvent.ACTION_UP)
                    else n.bridge.dispatchTouch(0f, 0f, MotionEvent.ACTION_CANCEL)
                }
                activeNode = null; draggingNode = null
                true
            }
            else -> false
        }
    }

    private fun tapAt(node: SpatialPanelNode, px: FloatArray) {
        node.bridge.dispatchTouch(px[0], px[1], MotionEvent.ACTION_DOWN)
        node.bridge.dispatchTouch(px[0], px[1], MotionEvent.ACTION_UP)
    }

    // ------------------------------------------------------------------
    // PanelHost
    // ------------------------------------------------------------------

    override fun hostContext() = this

    override fun pickFile(mimes: Array<String>, onPicked: (Uri?) -> Unit) {
        pendingPick = onPicked
        runCatching { fileLauncher.launch(mimes) }
            .onFailure { onPicked(null) }
    }

    override fun pickImage(onPicked: (Uri?) -> Unit) = pickFile(arrayOf("image/*"), onPicked)

    override fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
        pendingPerm = onResult
        runCatching { permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            .onFailure { onResult(false) }
    }

    override fun openApp(app: VrApp, param: String?, title: String?) {
        openAppPanel(app, param)
    }

    override fun closeApp(app: VrApp) {
        closePanel("app_${app.appId}")
    }

    override fun profile(): QualityProfile = PerformanceMonitor.profile.value

    companion object {
        const val BAR = "bar"
        const val MAIN = "main"
        const val SETTINGS = "settings"
        const val SYSTEM = "system"
    }
}
