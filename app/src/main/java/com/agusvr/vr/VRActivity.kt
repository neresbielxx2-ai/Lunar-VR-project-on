package com.agusvr.vr

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.agusvr.browser.BrowserPanel
import com.agusvr.camera.CameraEngine
import com.agusvr.files.FilesPanel
import com.agusvr.hand.HandFrame
import com.agusvr.hand.HandTrackingEngine
import com.agusvr.handlab.HandLabLink
import com.agusvr.handlab.HandLabPanel
import com.agusvr.home.HomeController
import com.agusvr.library.GamePlayerPanel
import com.agusvr.library.LibraryPanel
import com.agusvr.modellab.ModelLabPanel
import com.agusvr.performance.PerformanceMonitor
import com.agusvr.point.PointInteraction
import com.agusvr.runtime.AgusBus
import com.agusvr.runtime.AgusEvent
import com.agusvr.runtime.RuntimeCore
import com.agusvr.settings.SettingsPanel
import com.agusvr.settings.SettingsRepo
import com.agusvr.runtime.BuildFlags
import com.agusvr.spatial.AtmosphereView
import com.agusvr.spatial.EnvironmentBackdropView
import com.agusvr.spatial.EnvironmentRoom
import com.agusvr.spatial.GyroParallax
import com.agusvr.spatial.SpatialOverlayView
import com.agusvr.status.StatusPanel
import com.agusvr.store.StorePanel
import com.agusvr.ui.Panels
import com.agusvr.ui.PanelHost
import com.agusvr.ui.dialogs.AgusDialogs
import com.agusvr.ui.viewers.ImageViewerPanel
import com.agusvr.ui.viewers.TextViewerPanel
import com.agusvr.util.Logx
import com.agusvr.util.dp
import com.agusvr.windows.DisposableView
import com.agusvr.windows.PanelFactory
import com.agusvr.windows.SpatialWindowManager
import com.agusvr.windows.VrApp
import com.agusvr.performance.QualityProfile
import com.agusvr.R
import kotlinx.coroutines.launch

/**
 * The VR/AR operating environment (module: AgusVRRuntime + AgusSpatialUI).
 *
 * Layers, back to front:
 *  1. rear-camera passthrough (PreviewView) or the virtual environment;
 *  2. world layer — home tiles + floating spatial windows;
 *  3. HUD chips and quick actions;
 *  4. atmosphere particles;
 *  5. spatial toasts;
 *  6. ray/hand overlay (never interactive).
 *
 * Real touches and the synthesized hand stream both flow through the same
 * view tree, so everything (windows, WebView games, sliders) is operable by
 * finger or by hand.
 */
class VRActivity : AppCompatActivity(), PanelHost {

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private var backdrop: EnvironmentBackdropView? = null
    private lateinit var room: EnvironmentRoom
    private lateinit var contentHost: FrameLayout
    private lateinit var worldLayer: FrameLayout
    private lateinit var hudLayer: FrameLayout
    private lateinit var atmosphere: AtmosphereView
    private lateinit var toastLayer: FrameLayout
    private lateinit var overlay: SpatialOverlayView

    private val cameraEngine = CameraEngine()
    private var handEngine: HandTrackingEngine? = null
    private lateinit var point: PointInteraction
    private lateinit var windows: SpatialWindowManager
    private lateinit var home: HomeController
    private lateinit var hud: HudController
    private lateinit var toasts: com.agusvr.notifications.SpatialToasts
    private var gyro: GyroParallax? = null

    private var lastFrame: HandFrame? = null
    private var lastHudUpdate = 0L
    private var looping = false
    private var cameraStartAttempted = false

    // ---- activity result launchers ----
    private var pendingFileCb: ((Uri?) -> Unit)? = null
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cb = pendingFileCb
            pendingFileCb = null
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {
                }
            }
            cb?.invoke(uri)
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingNotifCb?.invoke(granted)
            pendingNotifCb = null
        }
    private var pendingNotifCb: ((Boolean) -> Unit)? = null

    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showCameraDeniedFlow()
        }

    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuntimeCore.setVrRunning(true)
        Panels.host = this

        buildLayers()

        point = PointInteraction(root)
        HandLabLink.point = point

        windows = SpatialWindowManager(worldLayer, panelFactory, contentHost)
        StatusPanel.windowStatsProvider = { windows.screenStats() }

        home = HomeController(worldLayer) { app -> openApp(app) }
        home.build()

        hud = HudController(
            hudLayer,
            onHome = { goHome() },
            onToggleHand = { toggleHandTracking() },
            onStatus = { openApp(VrApp.STATUS) },
            onExit = { finish() }
        )
        hud.handStateProvider = object : HudController.HandToggleState {
            override fun handEnabled(): Boolean = SettingsRepo.handEnabled
        }
        toasts = com.agusvr.notifications.SpatialToasts(toastLayer)

        gyro = GyroParallax(this)
        gyro?.start(gyroCallback)

        applySettingsLive()
        observeBus()
        setupBackHandling()

        // layout → coordinate mapper for hand tracking
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            handEngine?.mapper?.update(r - l, b - t)
        }

        enterImmersive()
        startCameraFlow()
        Logx.i("VR", "activity created")
    }

    private fun buildLayers() {
        root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.parseColor("#04070E"))

        room = EnvironmentRoom(this)
        root.addView(room, matchParent())

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            visibility = View.GONE
        }
        root.addView(previewView, matchParent())

        worldLayer = FrameLayout(this)
        root.addView(worldLayer, matchParent())

        hudLayer = FrameLayout(this)
        root.addView(hudLayer, matchParent())

        atmosphere = AtmosphereView(this)
        root.addView(atmosphere, matchParent())

        toastLayer = FrameLayout(this)
        root.addView(toastLayer, matchParent())

        overlay = SpatialOverlayView(this)
        root.addView(overlay, matchParent())

        // invisible host: curved windows keep their content attached here so
        // engine-backed views (WebView) initialize; CurvedSurface presents it.
        contentHost = FrameLayout(this).apply { visibility = View.INVISIBLE }
        root.addView(contentHost, matchParent())

        setContentView(root)
        root.keepScreenOn = true
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ------------------------------------------------------------------
    // Camera permission flow (rear camera only)
    // ------------------------------------------------------------------

    private fun startCameraFlow() {
        val caps = RuntimeCore.capabilities
        when {
            caps?.hasRearCamera != true -> {
                RuntimeCore.forceVirtualEnvironment = true
                enterVirtualEnvironment("Nenhuma câmera traseira disponível — usando o ambiente virtual.")
            }
            CameraEngine.hasPermission(this) -> startCamera()
            else -> showCameraRationale()
        }
    }

    private fun showCameraRationale() {
        val (dialog, rootL) = AgusDialogs.custom(this, getString(R.string.cam_perm_title))
        rootL.addView(com.agusvr.ui.widgets.AgusWidgets.bodyText(this, getString(R.string.cam_perm_body)))
        val buttons = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val lp = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8f) }
        buttons.addView(com.agusvr.ui.widgets.AgusWidgets.primaryButton(this, "Permitir câmera traseira") {
            dialog.dismiss()
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }, lp)
        buttons.addView(com.agusvr.ui.widgets.AgusWidgets.ghostButton(this, getString(R.string.cam_perm_continue_without)) {
            dialog.dismiss()
            RuntimeCore.forceVirtualEnvironment = true
            enterVirtualEnvironment("Ambiente virtual ativado. Você pode permitir a câmera depois, nas Configurações.")
        }, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8f) })
        rootL.addView(buttons, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14f) })
    }

    private fun showCameraDeniedFlow() {
        val (dialog, rootL) = AgusDialogs.custom(this, "Permissão de câmera negada")
        rootL.addView(com.agusvr.ui.widgets.AgusWidgets.bodyText(this,
            "Sem a câmera traseira o Agus VR usa o ambiente virtual e o rastreamento de mãos fica indisponível. Nada é simulado: você pode conceder a permissão quando quiser."))
        val mkLp = { android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8f) } }
        val buttons = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        buttons.addView(com.agusvr.ui.widgets.AgusWidgets.primaryButton(this, "Tentar novamente") {
            dialog.dismiss()
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }, mkLp())
        buttons.addView(com.agusvr.ui.widgets.AgusWidgets.ghostButton(this, getString(R.string.cam_perm_open_settings)) {
            dialog.dismiss()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            } catch (t: Throwable) {
                AgusDialogs.info(this, "Configurações", "Não foi possível abrir as configurações do app.")
            }
        }, mkLp())
        buttons.addView(com.agusvr.ui.widgets.AgusWidgets.ghostButton(this, getString(R.string.cam_perm_continue_without)) {
            dialog.dismiss()
            RuntimeCore.forceVirtualEnvironment = true
            enterVirtualEnvironment("Continuando no ambiente virtual.")
        }, mkLp())
        rootL.addView(buttons, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6f) })
    }

    private fun startCamera() {
        if (cameraStartAttempted) return
        cameraStartAttempted = true
        RuntimeCore.forceVirtualEnvironment = false
        backdrop?.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        ensureHandEngine()
        cameraEngine.start(this, this, previewView)
        root.postDelayed({
            if (!cameraEngine.isBound()) {
                cameraStartAttempted = false
                val err = cameraEngine.lastError ?: "falha desconhecida"
                RuntimeCore.setCameraActive(false)
                enterVirtualEnvironment("Câmera traseira falhou ($err) — usando o ambiente virtual.")
            } else {
                RuntimeCore.setCameraActive(true)
                room.setPassthrough(true)
                handEngine?.start()
            }
        }, 2500)
    }

    private fun enterVirtualEnvironment(reason: String) {
        RuntimeCore.setCameraActive(false)
        RuntimeCore.setHandState(RuntimeCore.HandEngineState.DISABLED)
        previewView.visibility = View.GONE
        room.setPassthrough(false)
        if (backdrop == null) {
            backdrop = EnvironmentBackdropView(this)
            root.addView(backdrop, 0, matchParent())
        }
        backdrop?.visibility = View.VISIBLE
        AgusBus.toast("Ambiente virtual", reason, R.drawable.ic_orbit, AgusEvent.ToastKind.INFO)
        hud.setHint("mãos exigem a câmera traseira · toque para interagir")
    }

    private fun ensureHandEngine() {
        if (handEngine != null) return
        if (!BuildFlags.HAND_TRACKING) {
            // UI-first release: the hand pipeline ships dormant on purpose.
            RuntimeCore.setHandState(RuntimeCore.HandEngineState.DISABLED)
            hud.setHint("interface espacial 3d · toque para interagir · mãos em atualização futura")
            return
        }
        val caps = RuntimeCore.capabilities
        if (caps?.handModelPresent != true) {
            RuntimeCore.setHandState(RuntimeCore.HandEngineState.UNSUPPORTED)
            hud.setHint("modelo de mãos ausente neste build · toque para interagir")
            return
        }
        if (!SettingsRepo.handEnabled) {
            RuntimeCore.setHandState(RuntimeCore.HandEngineState.DISABLED)
            return
        }
        val engine = HandTrackingEngine(this)
        engine.setListener(object : HandTrackingEngine.Listener {
            override fun onHandFrame(frame: HandFrame) {
                lastFrame = frame
                point.onHandFrame(frame)
            }
        })
        engine.mapper.update(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1))
        cameraEngine.setFrameListener(engine)
        handEngine = engine
        RuntimeProfileDivisorBridge.install()
    }

    private fun toggleHandTracking() {
        if (!BuildFlags.HAND_TRACKING) {
            AgusBus.toast("Rastreamento de mãos",
                "Chega em uma atualização futura — esta build é focada na interface espacial 3d curva.",
                R.drawable.ic_handlab, AgusEvent.ToastKind.INFO)
            SettingsRepo.handEnabled = false
            AgusBus.post(AgusEvent.SettingsChanged)
            return
        }
        SettingsRepo.handEnabled = !SettingsRepo.handEnabled
        if (SettingsRepo.handEnabled) {
            if (RuntimeCore.useVirtualEnvironment()) {
                AgusBus.toast("Mãos indisponíveis", "O rastreamento de mãos precisa da câmera traseira ativa.",
                    R.drawable.ic_handlab, AgusEvent.ToastKind.INFO)
                SettingsRepo.handEnabled = false
                return
            }
            cameraStartAttempted = false
            startCamera()
            AgusBus.toast("Rastreamento de mãos", "ativado", R.drawable.ic_handlab, AgusEvent.ToastKind.SUCCESS)
        } else {
            handEngine?.stop()
            RuntimeCore.setHandState(RuntimeCore.HandEngineState.DISABLED)
            lastFrame = null
            AgusBus.toast("Rastreamento de mãos", "desativado", R.drawable.ic_handlab, AgusEvent.ToastKind.INFO)
        }
        AgusBus.post(AgusEvent.SettingsChanged)
    }

    private fun goHome() {
        if (windows.hasWindows()) {
            windows.closeAll()
        }
        home.setVisible(true)
        overlay.setHologramVisible(true)
    }

    private val gyroCallback = object : GyroParallax.Callback {
        override fun onParallax(yawDeg: Float, pitchDeg: Float) {
            home.parallaxYaw = yawDeg * 0.4f
            home.parallaxPitch = pitchDeg * 0.4f
            worldLayer.rotationY = -yawDeg * 0.30f
            worldLayer.rotationX = pitchDeg * 0.22f
            worldLayer.cameraDistance = root.width * 2.4f
            room.parallax(yawDeg, pitchDeg)
            home.relayout()
        }
    }

    // ------------------------------------------------------------------
    // Frame loop
    // ------------------------------------------------------------------

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            PerformanceMonitor.onFrame()
            overlay.tick(lastFrame, point)
            windows.tick()
            atmosphere.invalidate()
            backdrop?.invalidate()
            val now = SystemClock.uptimeMillis()
            if (now - lastHudUpdate > 250) {
                lastHudUpdate = now
                hud.update(PerformanceMonitor.sample.value)
                syncHomeVisibility()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun syncHomeVisibility() {
        val hasWin = windows.hasWindows()
        if (hasWin && home.isVisible()) {
            home.setVisible(false)
            overlay.setHologramVisible(false)
        } else if (!hasWin && !home.isVisible()) {
            home.setVisible(true)
            overlay.setHologramVisible(true)
        }
    }

    // ------------------------------------------------------------------
    // Bus & settings
    // ------------------------------------------------------------------

    private fun observeBus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgusBus.events.collect { ev ->
                    when (ev) {
                        is AgusEvent.Toast -> toasts.show(ev.iconRes, ev.title, ev.message, ev.kind)
                        is AgusEvent.OpenApp -> {
                            val app = VrApp.byId(ev.appId)
                            if (app != null) openApp(app, ev.param)
                            else Logx.w("VR", "unknown appId ${ev.appId}")
                        }
                        AgusEvent.SettingsChanged -> applySettingsLive()
                        AgusEvent.LibraryChanged, AgusEvent.ModelsChanged -> { /* panels self-refresh */ }
                    }
                }
            }
        }
    }

    private fun applySettingsLive() {
        val profile = PerformanceMonitor.profile.value
        overlay.setProfile(profile)
        atmosphere.setProfile(profile)
        home.relayout()
        windows.relayout()
        gyro?.setEnabled(SettingsRepo.effects && profile.parallaxEnabled)
        handEngine?.restartIfSettingsChanged()
        if (cameraEngine.isBound()) cameraEngine.applyTierChange(this, this, previewView)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val focused = windows.focused()
                if (focused != null && focused.app == VrApp.BROWSER) {
                    val browser = focused.contentView as? BrowserPanel
                    if (browser?.onBackPressedInBrowser() == true) return
                }
                if (focused != null) {
                    windows.close(focused.id)
                    return
                }
                finish()
            }
        })
    }

    // ------------------------------------------------------------------
    // PanelHost
    // ------------------------------------------------------------------

    override fun hostContext() = this

    override fun pickFile(mimes: Array<String>, onPicked: (Uri?) -> Unit) {
        pendingFileCb = onPicked
        try {
            filePickerLauncher.launch(if (mimes.isEmpty()) arrayOf("*/*") else mimes)
        } catch (t: Throwable) {
            pendingFileCb = null
            Logx.w("VR", "file picker failed", t)
            try {
                pendingFileCb = onPicked
                filePickerLauncher.launch(arrayOf("*/*"))
            } catch (t2: Throwable) {
                pendingFileCb = null
                onPicked(null)
                AgusDialogs.info(this, "Seleção de arquivo",
                    "Nenhum seletor de arquivos disponível neste dispositivo (${t2.message}).")
            }
        }
    }

    override fun pickImage(onPicked: (Uri?) -> Unit) = pickFile(arrayOf("image/*"), onPicked)

    override fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            onResult(true)
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
            return
        }
        pendingNotifCb = onResult
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun openApp(app: VrApp, param: String?, title: String?) {
        windows.open(app, param, title)
        syncHomeVisibility()
    }

    override fun closeApp(app: VrApp) {
        for (w in windows.openWindows) if (w.app == app) windows.close(w.id)
    }

    override fun profile(): QualityProfile = PerformanceMonitor.profile.value

    private val panelFactory = object : PanelFactory {
        override fun create(app: VrApp, param: String?, selfClose: () -> Unit): View? = when (app) {
            VrApp.STORE -> StorePanel(this@VRActivity)
            VrApp.LIBRARY -> LibraryPanel(this@VRActivity)
            VrApp.BROWSER -> BrowserPanel(this@VRActivity, param)
            VrApp.FILES -> FilesPanel(this@VRActivity)
            VrApp.SETTINGS -> SettingsPanel(this@VRActivity) { applySettingsLive() }
            VrApp.PERFORMANCE -> com.agusvr.performance.PerformancePanel(this@VRActivity)
            VrApp.HANDLAB -> HandLabPanel(this@VRActivity)
            VrApp.MODELLAB -> ModelLabPanel(this@VRActivity, param)
            VrApp.STATUS -> StatusPanel(this@VRActivity)
            VrApp.GAME -> GamePlayerPanel(this@VRActivity, param)
            VrApp.TEXTVIEW -> TextViewerPanel(this@VRActivity, param)
            VrApp.IMAGEVIEW -> ImageViewerPanel(this@VRActivity, param)
        }

        override fun dispose(app: VrApp, content: View?) {
            (content as? DisposableView)?.dispose()
        }
    }

    // ------------------------------------------------------------------
    // Touch routing (real finger always wins over the synthesized stream)
    // ------------------------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        point.onRealTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        enterImmersive()
        looping = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        PerformanceMonitor.start()
        gyro?.start(gyroCallback)
        if (cameraEngine.isBound()) {
            RuntimeCore.setCameraActive(true)
            handEngine?.start()
        }
    }

    override fun onPause() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        PerformanceMonitor.stop()
        gyro?.stop()
        handEngine?.stop()
        lastFrame = null
        super.onPause()
    }

    override fun onDestroy() {
        Panels.host = null
        HandLabLink.point = null
        StatusPanel.windowStatsProvider = null
        windows.closeAll()
        point.release()
        handEngine?.release()
        handEngine = null
        cameraEngine.release()
        home.destroy()
        toasts.clearAll()
        RuntimeCore.setVrRunning(false)
        RuntimeCore.setCameraActive(false)
        super.onDestroy()
    }
}

/** Installs the analysis-frame divisor from the live performance profile. */
private object RuntimeProfileDivisorBridge {
    fun install() {
        com.agusvr.hand.RuntimeProfileDivisor.provider = {
            PerformanceMonitor.profile.value.analysisDivisor
        }
    }
}
