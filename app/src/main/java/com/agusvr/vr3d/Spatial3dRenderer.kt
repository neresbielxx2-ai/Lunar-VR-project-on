package com.agusvr.vr3d

import android.opengl.Matrix
import android.view.Choreographer
import android.view.MotionEvent
import android.view.TextureView
import com.agusvr.settings.SettingsRepo
import com.agusvr.util.Logx
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import kotlin.math.tan

/**
 * The 3DOF spatial renderer (module: AgusVRRuntime/3D).
 *
 * World graph (menus are NEVER children of the camera):
 *
 *   WorldRoot (identity)
 *   ├── CameraRig ── Camera(mono | left | right)     ← 3DOF quaternion only
 *   ├── MainMenu / SettingsMenu / SystemMenu / FloatingPanels
 *
 * Stereo SBS: two views with separate viewports (left/right half), separate
 * cameras offset by ±IPD/2 on the rig's X axis, same scene, same frame —
 * real stereoscopic depth, no duplicated or misaligned menus.
 */
class Spatial3dRenderer(
    private val textureView: TextureView,
    val tracker: HeadTracker3Dof,
    private val qualityCols: Int = 10,
    private val shadows: Boolean = true
) {

    enum class Mode { MONO, SBS }

    /** Live engine reference for panel/bridge creation. */
    fun engineOrNull(): Engine? = engine

    var mode: Mode = Mode.MONO
        set(value) {
            if (field != value) {
                field = value
                configureViews()
            }
        }

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var skybox: Skybox? = null
    private var swapChain: SwapChain? = null
    private var uiHelper: UiHelper? = null

    private var viewMono: View? = null
    private var viewL: View? = null
    private var viewR: View? = null
    private var camMono: Camera? = null
    private var camL: Camera? = null
    private var camR: Camera? = null
    private var camMonoEntity = 0
    private var camLEntity = 0
    private var camREntity = 0

    val materials = lazy { engine?.let { PanelMaterialLibrary(it) } }
    private val nodes = LinkedHashMap<String, SpatialPanelNode>()
    var focusedId: String? = null

    private var vpW = 1
    private var vpH = 1
    private var ready = false
    private var looping = false
    private var lastFrameNs = 0L
    private var uploadsThisFrame = 0

    // picking scratch
    private val vpMat = FloatArray(16)
    private val viewMat = FloatArray(16)
    private val projMat = FloatArray(16)
    private val invVp = FloatArray(16)
    private val modelMat = FloatArray(16)
    private val rigMat = FloatArray(16)
    private val eyeMat = FloatArray(16)
    private val tmp4 = FloatArray(4)

    // gaze dwell (SBS)
    var gazeDwellMs = 1150
    private var gazeNode: SpatialPanelNode? = null
    private var gazeAccum = 0L
    var onGazeClick: ((SpatialPanelNode, FloatArray) -> Unit)? = null

    val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {
            vpW = w; vpH = h
            uiHelper?.let { }
            createSwapChain(surface)
        }
        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {
            vpW = w; vpH = h
            configureViews()
        }
        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
            engine?.let { e -> swapChain?.let { e.destroySwapChain(it) } }
            swapChain = null
            ready = false
            return true
        }
        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
    }

    fun init() {
        val e = Engine.create()
        engine = e
        renderer = e.createRenderer()
        scene = e.createScene()
        skybox = Skybox.Builder().color(0.014f, 0.020f, 0.034f, 1f).build(e)
        scene?.skybox = skybox

        camMonoEntity = EntityManager.get().create()
        camLEntity = EntityManager.get().create()
        camREntity = EntityManager.get().create()
        camMono = e.createCamera(camMonoEntity)
        camL = e.createCamera(camLEntity)
        camR = e.createCamera(camREntity)

        viewMono = e.createView().apply { scene = this@Spatial3dRenderer.scene; camera = camMono }
        viewL = e.createView().apply { scene = this@Spatial3dRenderer.scene; camera = camL }
        viewR = e.createView().apply { scene = this@Spatial3dRenderer.scene; camera = camR }

        val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        helper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: android.view.Surface) {
                engine?.let { e -> swapChain?.let { e.destroySwapChain(it) } }
                createSwapChain(surface)
            }
            override fun onDetachedFromSurface() {
                engine?.let { e -> swapChain?.let { e.destroySwapChain(it) } }
                swapChain = null
                ready = false
            }
            override fun onResized(w: Int, h: Int) {
                vpW = w; vpH = h
                configureViews()
            }
        }
        helper.attachTo(textureView)
        uiHelper = helper
        configureViews()
        tracker.start()
    }

    private fun createSwapChain(surface: Any) {
        val e = engine ?: return
        runCatching { swapChain?.let { e.destroySwapChain(it) } }
        swapChain = e.createSwapChain(surface)
        ready = true
    }

    private fun configureViews() {
        if (vpW <= 0 || vpH <= 0) return
        when (mode) {
            Mode.MONO -> {
                viewMono?.viewport = Viewport(0, 0, vpW, vpH)
            }
            Mode.SBS -> {
                val half = vpW / 2
                viewL?.viewport = Viewport(0, 0, half, vpH)
                viewR?.viewport = Viewport(half, 0, vpW - half, vpH)
            }
        }
        updateProjections()
    }

    private fun updateProjections() {
        if (vpW <= 0 || vpH <= 0) return
        val aspectMono = vpW.toDouble() / vpH.toDouble()
        val aspectEye = (vpW / 2.0) / vpH.toDouble()
        camMono?.setProjection(55.0, aspectMono, 0.05, 40.0, Camera.Fov.VERTICAL)
        camL?.setProjection(55.0, aspectEye, 0.05, 40.0, Camera.Fov.VERTICAL)
        camR?.setProjection(55.0, aspectEye, 0.05, 40.0, Camera.Fov.VERTICAL)
    }

    // ------------------------------------------------------------------
    // Panels
    // ------------------------------------------------------------------

    fun addPanel(id: String, node: SpatialPanelNode) {
        nodes[id] = node
        focusedId = id
    }

    fun removePanel(id: String) {
        nodes.remove(id)
        if (focusedId == id) focusedId = nodes.keys.lastOrNull()
    }

    fun panel(id: String): SpatialPanelNode? = nodes[id]
    fun allPanels(): List<SpatialPanelNode> = nodes.values.toList()

    // ------------------------------------------------------------------
    // Frame loop
    // ------------------------------------------------------------------

    fun startLoop() {
        looping = true
        lastFrameNs = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stopLoop() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            val dt = ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0.0005f, 0.1f)
            lastFrameNs = frameTimeNanos
            tracker.update(dt)
            stepNodes(dt)
            if (ready && uiHelper?.isReadyToRender == true) {
                renderFrame()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stepNodes(dt: Float) {
        uploadsThisFrame = 0
        val it = nodes.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val alive = entry.value.tick(dt)
            if (!alive) {
                entry.value.destroy()
                it.remove()
                if (focusedId == entry.key) focusedId = nodes.keys.lastOrNull()
            }
        }
        // texture upload budget: focused first, max 2 per frame (perf)
        val ordered = nodes.values.sortedByDescending { it === nodes[focusedId] }
        for (n in ordered) {
            if (uploadsThisFrame >= 2) break
            val before = n.bridge.uploads
            n.bridge.tick(allowRedraw = true)
            if (n.bridge.uploads != before) uploadsThisFrame++
        }
        if (mode == Mode.SBS) stepGaze(dt)
    }

    private fun renderFrame() {
        val r = renderer ?: return
        val sc = swapChain ?: return
        // camera rig = 3DOF orientation only (no translation ever)
        QMath.toMatrix(tracker.rigQuaternion, rigMat)
        applyCameraTransforms()
        if (!r.beginFrame(sc, System.nanoTime())) return
        when (mode) {
            Mode.MONO -> viewMono?.let { r.render(it) }
            Mode.SBS -> {
                viewL?.let { r.render(it) }
                viewR?.let { r.render(it) }
            }
        }
        r.endFrame()
    }

    private fun applyCameraTransforms() {
        val tcm = engine?.transformManager ?: return
        tcm.setTransform(tcm.getInstance(camMonoEntity), rigMat)
        val ipd = (SettingsRepo.ipdMm / 1000f) / 2f
        // left eye: rig * T(-ipd,0,0); right eye: rig * T(+ipd,0,0)
        Matrix.setIdentityM(eyeMat, 0)
        Matrix.translateM(eyeMat, 0, -ipd, 0f, 0f)
        Matrix.multiplyMM(modelMat, 0, rigMat, 0, eyeMat, 0)
        tcm.setTransform(tcm.getInstance(camLEntity), modelMat)
        Matrix.setIdentityM(eyeMat, 0)
        Matrix.translateM(eyeMat, 0, ipd, 0f, 0f)
        Matrix.multiplyMM(modelMat, 0, rigMat, 0, eyeMat, 0)
        tcm.setTransform(tcm.getInstance(camREntity), modelMat)
    }

    // ------------------------------------------------------------------
    // Picking
    // ------------------------------------------------------------------

    /** Ray origin/dir for a screen point in the current mode (mono uses full viewport). */
    fun screenRay(sx: Float, sy: Float): Pair<FloatArray, FloatArray> {
        val cam = camMono ?: return Pair(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, -1f))
        val aspect = if (mode == Mode.SBS) (vpW / 2.0 / vpH) else (vpW.toDouble() / vpH)
        buildProjection(projMat, aspect)
        cam.getModelMatrix(modelMat)
        Matrix.invertM(viewMat, 0, modelMat, 0)
        Matrix.multiplyMM(vpMat, 0, projMat, 0, viewMat, 0)
        Matrix.invertM(invVp, 0, vpMat, 0)
        val nx = (sx / vpW) * 2f - 1f
        val ny = 1f - (sy / vpH) * 2f
        val p0 = unproject(nx, ny, -1f)
        val p1 = unproject(nx, ny, 1f)
        val dx = p1[0] - p0[0]; val dy = p1[1] - p0[1]; val dz = p1[2] - p0[2]
        val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        return Pair(p0, floatArrayOf(dx / len, dy / len, dz / len))
    }

    /** Center-of-gaze ray (SBS dwell selection). */
    fun gazeRay(): Pair<FloatArray, FloatArray> {
        camL?.getModelMatrix(modelMat) ?: return Pair(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, -1f))
        val o = floatArrayOf(modelMat[12], modelMat[13], modelMat[14])
        val d = floatArrayOf(-modelMat[8], -modelMat[9], -modelMat[10])
        return Pair(o, d)
    }

    private fun buildProjection(out: FloatArray, aspect: Double) {
        val fovY = Math.toRadians(55.0)
        val top = Math.tan(fovY / 2) * 0.05
        // use Android helper for exactness with Camera.setProjection(fov,aspect,...)
        val f = 1.0 / tan(fovY / 2)
        android.opengl.Matrix.setIdentityM(out, 0)
        out[0] = (f / aspect).toFloat()
        out[5] = f.toFloat()
        out[10] = ((40.0 + 0.05) / (0.05 - 40.0)).toFloat()
        out[11] = -1f
        out[14] = (2.0 * 40.0 * 0.05 / (0.05 - 40.0)).toFloat()
        out[15] = 0f
        // silence unused
        if (top < 0) out[15] = 0f
    }

    private fun unproject(nx: Float, ny: Float, nz: Float): FloatArray {
        tmp4[0] = nx; tmp4[1] = ny; tmp4[2] = nz; tmp4[3] = 1f
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, invVp, 0, tmp4, 0)
        if (Math.abs(out[3]) < 1e-6) return floatArrayOf(out[0], out[1], out[2])
        return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }

    /** Front-most panel hit by a screen point + content px, or null. */
    fun pickPanel(sx: Float, sy: Float): Pair<SpatialPanelNode, FloatArray>? {
        val (o, d) = screenRay(sx, sy)
        var best: Pair<SpatialPanelNode, FloatArray>? = null
        var bestDist = Float.MAX_VALUE
        for (n in nodes.values) {
            if (n.closing) continue
            val px = n.pick(o, d) ?: continue
            if (n.distance < bestDist) {
                bestDist = n.distance
                best = Pair(n, px)
            }
        }
        return best
    }

    private fun stepGaze(dt: Float) {
        val (o, d) = gazeRay()
        var hit: SpatialPanelNode? = null
        var hitPx: FloatArray? = null
        var bestDist = Float.MAX_VALUE
        for (n in nodes.values) {
            if (n.closing) continue
            val px = n.pick(o, d) ?: continue
            if (n.distance < bestDist) { bestDist = n.distance; hit = n; hitPx = px }
        }
        val hovered = hit
        for (n in nodes.values) n.hovered = n === hovered
        if (hovered != null && hovered === gazeNode && hitPx != null) {
            gazeAccum += (dt * 1000).toLong()
            if (gazeAccum >= gazeDwellMs) {
                gazeAccum = 0
                onGazeClick?.invoke(hovered, hitPx)
            }
        } else {
            gazeNode = hovered
            gazeAccum = 0
        }
    }

    /** Synthetic click for gaze/dwell at content px. */
    fun clickAt(node: SpatialPanelNode, px: FloatArray) {
        node.bridge.dispatchTouch(px[0], px[1], MotionEvent.ACTION_DOWN)
        node.bridge.dispatchTouch(px[0], px[1], MotionEvent.ACTION_UP)
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    fun destroy() {
        stopLoop()
        tracker.stop()
        for (n in nodes.values.toList()) n.destroy()
        nodes.clear()
        val e = engine ?: return
        runCatching {
            uiHelper?.detach()
            viewMono?.let { e.destroyView(it) }
            viewL?.let { e.destroyView(it) }
            viewR?.let { e.destroyView(it) }
            e.destroyEntity(camMonoEntity)
            e.destroyEntity(camLEntity)
            e.destroyEntity(camREntity)
            skybox?.let { e.destroySkybox(it) }
            scene?.let { e.destroyScene(it) }
            renderer?.let { e.destroyRenderer(it) }
            swapChain?.let { e.destroySwapChain(it) }
            if (materials.isInitialized()) materials.value?.destroy()
        }
        engine = null
        Logx.i("Renderer3D", "destroyed")
    }
}
