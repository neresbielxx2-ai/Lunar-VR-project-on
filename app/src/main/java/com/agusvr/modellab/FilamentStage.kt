package com.agusvr.modellab

import android.graphics.Bitmap
import android.opengl.Matrix
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import com.agusvr.util.Logx
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.TransformManager
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.filamat.MaterialBuilder
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** One manipulable object in the stage. */
class StageNode(
    val id: String,
    var name: String,
    val kind: String,              // "glb" | "gltf" | "obj"
    val fileName: String?,
    val rootEntity: Int,           // entity that carries the node transform
    val asset: FilamentAsset?,     // gltf/glb payload (null for obj)
    val renderEntity: Int,         // obj renderable entity (0 for gltf)
    val vb: VertexBuffer?,
    val ib: IndexBuffer?,
    val localMin: FloatArray,
    val localMax: FloatArray
) {
    val position = FloatArray(3)
    val euler = FloatArray(3)
    var scale: Float = 1f
    var baseFitScale: Float = 1f
    val matrix = FloatArray(16)
}

/**
 * The 3D stage of AGUS MODEL LAB (module: AgusModelLab).
 *
 * Real Filament rendering on a TextureView (so the stage lives inside a
 * floating, transformable VR window): GLB/glTF via gltfio and OBJ via a
 * bundled parser + runtime-built material (filamat). Supports multiple
 * models, per-node move/rotate/scale, orbit camera, picking by projected
 * bounding boxes and bitmap snapshots for project covers.
 */
class FilamentStage(private val textureView: TextureView) {

    enum class Mode { ORBIT, MOVE, ROTATE, SCALE }

    interface Events {
        fun onStatus(message: String)
        fun onSelectionChanged(nodeId: String?)
        fun onNodesChanged()
    }

    var events: Events? = null
    var mode: Mode = Mode.ORBIT

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var cameraEntity = 0
    private var swapChain: SwapChain? = null
    private var uiHelper: UiHelper? = null
    private var skybox: Skybox? = null
    private val lightEntities = mutableListOf<Int>()

    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var materialProvider: UbershaderProvider? = null
    private var objMaterial: Material? = null
    private var objMaterialInstance: MaterialInstance? = null

    private val nodes = mutableListOf<StageNode>()
    private var selectedId: String? = null

    // camera orbit state
    private var camYaw = 0.6f
    private var camPitch = 0.32f
    private var camDist = 4.2f
    private val camTarget = floatArrayOf(0f, 0f, 0f)

    private var running = false
    private var vpWidth = 1
    private var vpHeight = 1

    // pending async gltf loads (one at a time)
    private data class PendingLoad(val node: StageNode, val asset: FilamentAsset)
    private var pending: PendingLoad? = null

    // touch state
    private var lastX = 0f
    private var lastY = 0f
    private var pinchDist = 0f
    private var pointerCount = 0

    val isReady: Boolean get() = engine != null && swapChain != null
    fun isBusy(): Boolean = pending != null
    fun selected(): StageNode? = nodes.firstOrNull { it.id == selectedId }
    fun allNodes(): List<StageNode> = nodes.toList()

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
            vpWidth = max(1, width); vpHeight = max(1, height)
            createEngine()
            startLoop()
        }

        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
            vpWidth = max(1, width); vpHeight = max(1, height)
            updateProjection()
        }

        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
            stopLoop()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
    }

    fun start() {
        if (textureView.isAvailable) {
            vpWidth = max(1, textureView.width); vpHeight = max(1, textureView.height)
            createEngine()
            startLoop()
        } else {
            textureView.surfaceTextureListener = surfaceListener
        }
        textureView.setOnTouchListener { _, e -> handleTouch(e); true }
    }

    fun destroy() {
        stopLoop()
        try {
            uiHelper?.detach()
            for (n in nodes.toList()) destroyNodeInternal(n)
            nodes.clear()
            resourceLoader?.destroy()
            assetLoader?.destroy()
            materialProvider?.destroyMaterials()
            materialProvider?.destroy()
            objMaterialInstance = null
            objMaterial?.let { engine?.destroyMaterial(it) }
            objMaterial = null
            skybox?.let { engine?.destroySkybox(it) }
            for (l in lightEntities) {
                engine?.destroyEntity(l)
                EntityManager.get().destroy(l)
            }
            lightEntities.clear()
            camera?.let {
                engine?.destroyCameraComponent(cameraEntity)
                EntityManager.get().destroy(cameraEntity)
            }
            renderer?.let { engine?.destroyRenderer(it) }
            view?.let { engine?.destroyView(it) }
            scene?.let { engine?.destroyScene(it) }
            swapChain?.let { engine?.destroySwapChain(it) }
            swapChain = null
            engine?.destroy()
            engine = null
        } catch (t: Throwable) {
            Logx.w("Stage", "destroy failed", t)
        }
        textureView.setOnTouchListener(null)
        events = null
    }

    private fun createEngine() {
        if (engine != null) return
        try {
            val e = Engine.create()
            engine = e
            renderer = e.createRenderer()
            scene = e.createScene()
            view = e.createView().apply { scene = this@FilamentStage.scene }
            cameraEntity = EntityManager.get().create()
            camera = e.createCamera(cameraEntity).apply {
                setExposure(16f, 1f / 125f, 100f)
            }
            view?.camera = camera
            view?.viewport = Viewport(0, 0, vpWidth, vpHeight)

            skybox = Skybox.Builder().color(0.016f, 0.027f, 0.055f, 1f).build(e)
            scene?.skybox = skybox

            // key light (sun) + cool fill — no IBL needed for a small studio
            val sun = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.SUN)
                .color(1f, 0.98f, 0.94f)
                .intensity(95_000f)
                .direction(0.2f, -1f, -0.35f)
                .castShadows(true)
                .build(e, sun)
            scene?.addEntity(sun)
            lightEntities += sun

            val fill = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(0.55f, 0.8f, 1f)
                .intensity(28_000f)
                .direction(-0.6f, -0.2f, 0.8f)
                .castShadows(false)
                .build(e, fill)
            scene?.addEntity(fill)
            lightEntities += fill

            val rim = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(0.85f, 0.6f, 1f)
                .intensity(12_000f)
                .direction(0.9f, 0.4f, -0.3f)
                .castShadows(false)
                .build(e, rim)
            scene?.addEntity(rim)
            lightEntities += rim

            materialProvider = UbershaderProvider(e)
            assetLoader = AssetLoader(e, materialProvider!!, EntityManager.get())
            resourceLoader = ResourceLoader(e)

            val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
            helper.renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: Surface) {
                    val eng = engine ?: return
                    swapChain?.let { eng.destroySwapChain(it) }
                    swapChain = eng.createSwapChain(surface)
                }

                override fun onDetachedFromSurface() {
                    val eng = engine ?: return
                    swapChain?.let { eng.destroySwapChain(it) }
                    swapChain = null
                }

                override fun onResized(width: Int, height: Int) {
                    vpWidth = max(1, width); vpHeight = max(1, height)
                    view?.viewport = Viewport(0, 0, vpWidth, vpHeight)
                    updateProjection()
                }
            }
            helper.attachTo(textureView)
            uiHelper = helper

            updateProjection()
            Logx.i("Stage", "filament engine created (${vpWidth}x${vpHeight})")
        } catch (t: Throwable) {
            Logx.e("Stage", "engine creation failed", t)
            events?.onStatus("Falha ao criar o motor 3D: ${t.message}")
            engine = null
        }
    }

    private fun updateProjection() {
        val cam = camera ?: return
        val aspect = vpWidth.toDouble() / vpHeight.toDouble()
        cam.setProjection(50.0, aspect, 0.05, 200.0, Camera.Fov.VERTICAL)
    }

    /** java.nio direct-buffer helper (NioUtils is package-private in filament 1.75.x). */
    private fun directBuffer(data: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
            .put(data).apply { rewind() }

    // ------------------------------------------------------------------
    // Render loop
    // ------------------------------------------------------------------

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            try {
                step(frameTimeNanos)
            } catch (t: Throwable) {
                Logx.w("Stage", "frame failed", t)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startLoop() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private var animationStartNs = 0L

    private fun step(frameTimeNanos: Long) {
        val r = renderer ?: return
        val sc = swapChain ?: return
        val v = view ?: return
        if (uiHelper?.isReadyToRender != true) return

        resourceLoader?.asyncUpdateLoad()
        assetLoader?.gc()
        finalizePending()

        // animations
        if (animationStartNs == 0L) animationStartNs = frameTimeNanos
        val elapsed = (frameTimeNanos - animationStartNs) / 1_000_000_000.0
        for (n in nodes) {
            val anim = n.asset?.instance?.animator ?: continue
            if (anim.animationCount > 0) {
                anim.applyAnimation(0, elapsed.toFloat())
                anim.updateBoneMatrices()
            }
        }
        updateCamera()

        if (r.beginFrame(sc, frameTimeNanos)) {
            r.render(v)
            r.endFrame()
        }
    }

    private fun finalizePending() {
        val p = pending ?: return
        val rl = resourceLoader ?: return
        if (rl.asyncGetLoadProgress() >= 1f) {
            pending = null
            try {
                val asset = p.asset
                scene?.addEntities(asset.entities)
                // fit node to unit size and center it
                val box: Box = asset.boundingBox
                val center = box.center
                val half = box.halfExtent
                val extent = max(half[0], max(half[1], half[2])) * 2f
                val fit = if (extent > 0.0001f) 1.6f / extent else 1f
                p.node.baseFitScale = fit
                p.node.scale = fit
                p.node.position[0] = -center[0] * fit
                p.node.position[1] = -center[1] * fit
                p.node.position[2] = -center[2] * fit
                p.node.localMin[0] = center[0] - half[0]; p.node.localMin[1] = center[1] - half[1]; p.node.localMin[2] = center[2] - half[2]
                p.node.localMax[0] = center[0] + half[0]; p.node.localMax[1] = center[1] + half[1]; p.node.localMax[2] = center[2] + half[2]
                ensureTransformInstance(p.node.rootEntity)
                applyTransform(p.node)
                events?.onStatus("modelo '${p.node.name}' carregado")
                events?.onNodesChanged()
            } catch (t: Throwable) {
                Logx.e("Stage", "finalize load failed", t)
                events?.onStatus("falha ao finalizar o modelo: ${t.message}")
            }
        }
    }

    private fun updateCamera() {
        val cam = camera ?: return
        val cp = cos(camPitch.toDouble())
        val ex = camTarget[0] + (camDist * cp * sin(camYaw.toDouble())).toFloat()
        val ey = camTarget[1] + (camDist * sin(camPitch.toDouble())).toFloat()
        val ez = camTarget[2] + (camDist * cp * cos(camYaw.toDouble())).toFloat()
        cam.lookAt(ex.toDouble(), ey.toDouble(), ez.toDouble(),
            camTarget[0].toDouble(), camTarget[1].toDouble(), camTarget[2].toDouble(),
            0.0, 1.0, 0.0)
    }

    // ------------------------------------------------------------------
    // Model loading
    // ------------------------------------------------------------------

    fun loadGlbFile(file: File): StageNode? {
        val bytes = try {
            file.readBytes()
        } catch (t: Throwable) {
            events?.onStatus("não foi possível ler ${file.name}")
            return null
        }
        return loadGlbBytes(bytes, file.nameWithoutExtension, file.name)
    }

    fun loadGlbBytes(bytes: ByteArray, name: String, fileName: String?): StageNode? {
        if (engine == null) {
            events?.onStatus("motor 3D ainda não está pronto — tente novamente")
            return null
        }
        if (pending != null) {
            events?.onStatus("outro modelo ainda está carregando")
            return null
        }
        return try {
            val buffer = directBuffer(bytes)
            val asset = assetLoader?.createAsset(buffer)
            if (asset == null) {
                events?.onStatus("arquivo GLB/glTF inválido ou não suportado")
                return null
            }
            if (!resourceLoader!!.asyncBeginLoad(asset)) {
                assetLoader?.destroyAsset(asset)
                events?.onStatus("falha ao iniciar o carregamento dos recursos")
                return null
            }
            val node = StageNode(
                id = UUID.randomUUID().toString().take(8),
                name = name,
                kind = "glb",
                fileName = fileName,
                rootEntity = asset.root,
                asset = asset,
                renderEntity = 0,
                vb = null, ib = null,
                localMin = floatArrayOf(-0.5f, -0.5f, -0.5f),
                localMax = floatArrayOf(0.5f, 0.5f, 0.5f)
            )
            nodes += node
            pending = PendingLoad(node, asset)
            events?.onStatus("carregando '$name'…")
            node
        } catch (t: Throwable) {
            Logx.e("Stage", "glb load failed", t)
            events?.onStatus("erro ao carregar GLB: ${t.message}")
            null
        }
    }

    /**
     * Loads a text .gltf. External resources (bin/textures) are resolved from
     * the same directory when present; missing references are reported clearly.
     */
    fun loadGltfFile(file: File): StageNode? {
        if (pending != null) {
            events?.onStatus("outro modelo ainda está carregando")
            return null
        }
        val bytes = try {
            file.readBytes()
        } catch (t: Throwable) {
            events?.onStatus("não foi possível ler ${file.name}")
            return null
        }
        return try {
            val buffer = directBuffer(bytes)
            val asset = assetLoader?.createAsset(buffer)
            if (asset == null) {
                events?.onStatus("arquivo .gltf inválido")
                return null
            }
            val rl = resourceLoader!!
            val dir = file.parentFile
            var missing = 0
            for (uri in asset.resourceUris) {
                val local = dir?.let { File(it, uri) }
                if (local != null && local.exists()) {
                    rl.addResourceData(uri, directBuffer(local.readBytes()))
                } else {
                    missing++
                }
            }
            if (missing > 0) {
                events?.onStatus("aviso: $missing recurso(s) externo(s) do .gltf não encontrado(s) (prefira .glb)")
            }
            if (!rl.asyncBeginLoad(asset)) {
                assetLoader?.destroyAsset(asset)
                events?.onStatus("falha ao iniciar o carregamento do .gltf")
                return null
            }
            val node = StageNode(
                id = UUID.randomUUID().toString().take(8),
                name = file.nameWithoutExtension,
                kind = "gltf",
                fileName = file.name,
                rootEntity = asset.root,
                asset = asset,
                renderEntity = 0,
                vb = null, ib = null,
                localMin = floatArrayOf(-0.5f, -0.5f, -0.5f),
                localMax = floatArrayOf(0.5f, 0.5f, 0.5f)
            )
            nodes += node
            pending = PendingLoad(node, asset)
            events?.onStatus("carregando '${node.name}'…")
            node
        } catch (t: Throwable) {
            Logx.e("Stage", "gltf load failed", t)
            events?.onStatus("erro ao carregar .gltf: ${t.message}")
            null
        }
    }

    fun loadObjMesh(meshRaw: ObjMesh, name: String, fileName: String?): StageNode? {
        val e = engine
        if (e == null) {
            events?.onStatus("motor 3D ainda não está pronto — tente novamente")
            return null
        }
        return try {
            val mesh = ObjParser.ensureNormals(meshRaw)
            val stride = 28 // float3 pos + float4 tangent
            val vArr = ByteArray(mesh.vertexCount * stride)
            val data = ByteBuffer.wrap(vArr).order(ByteOrder.nativeOrder())
            for (v in 0 until mesh.vertexCount) {
                data.putFloat(mesh.positions[v * 3])
                data.putFloat(mesh.positions[v * 3 + 1])
                data.putFloat(mesh.positions[v * 3 + 2])
                data.putFloat(mesh.tangents[v * 4])
                data.putFloat(mesh.tangents[v * 4 + 1])
                data.putFloat(mesh.tangents[v * 4 + 2])
                data.putFloat(mesh.tangents[v * 4 + 3])
            }

            val vb = VertexBuffer.Builder()
                .bufferCount(1)
                .vertexCount(mesh.vertexCount)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, stride)
                .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 12, stride)
                .build(e)
            vb.setBufferAt(e, 0, directBuffer(vArr))

            val iArr = ByteArray(mesh.indexCount * 2)
            val idxBytes = ByteBuffer.wrap(iArr).order(ByteOrder.nativeOrder())
            for (i in 0 until mesh.indexCount) idxBytes.putShort(mesh.indices[i])
            val ib = IndexBuffer.Builder()
                .indexCount(mesh.indexCount)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(e)
            ib.setBuffer(e, directBuffer(iArr))

            val mi = obtainObjMaterial(e) ?: run {
                events?.onStatus("não foi possível criar o material do OBJ")
                return null
            }

            val entity = EntityManager.get().create()
            RenderableManager.Builder(1)
                .material(0, mi)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
                .culling(false)
                .build(e, entity)
            scene?.addEntity(entity)

            val node = StageNode(
                id = UUID.randomUUID().toString().take(8),
                name = name,
                kind = "obj",
                fileName = fileName,
                rootEntity = entity,
                asset = null,
                renderEntity = entity,
                vb = vb, ib = ib,
                localMin = mesh.boundsMin.copyOf(),
                localMax = mesh.boundsMax.copyOf()
            )
            // fit to unit size
            val cx = (mesh.boundsMin[0] + mesh.boundsMax[0]) / 2f
            val cy = (mesh.boundsMin[1] + mesh.boundsMax[1]) / 2f
            val cz = (mesh.boundsMin[2] + mesh.boundsMax[2]) / 2f
            val extent = max(
                mesh.boundsMax[0] - mesh.boundsMin[0],
                max(mesh.boundsMax[1] - mesh.boundsMin[1], mesh.boundsMax[2] - mesh.boundsMin[2])
            )
            val fit = if (extent > 0.0001f) 1.6f / extent else 1f
            node.baseFitScale = fit
            node.scale = fit
            node.position[0] = -cx * fit
            node.position[1] = -cy * fit
            node.position[2] = -cz * fit
            ensureTransformInstance(entity)
            applyTransform(node)
            nodes += node
            select(node.id)
            events?.onStatus("modelo OBJ '$name' carregado (${mesh.vertexCount} vértices)")
            events?.onNodesChanged()
            node
        } catch (t: Throwable) {
            Logx.e("Stage", "obj load failed", t)
            events?.onStatus("erro ao carregar OBJ: ${t.message}")
            null
        }
    }

    private fun obtainObjMaterial(e: Engine): MaterialInstance? {
        objMaterialInstance?.let { return it }
        MaterialBuilder.init()
        try {
            val pkg = MaterialBuilder()
                .name("agusObjLit")
                .shading(MaterialBuilder.Shading.LIT)
                .doubleSided(true)
                .require(MaterialBuilder.VertexAttribute.POSITION)
                .require(MaterialBuilder.VertexAttribute.TANGENTS)
                .material(
                    """
                    fragment {
                        void material(inout MaterialInputs material) {
                            prepareMaterial(material);
                            material.baseColor = vec4(0.62, 0.76, 0.92, 1.0);
                            material.roughness = 0.42;
                            material.metallic = 0.12;
                            material.reflectance = 0.5;
                        }
                    }
                    """.trimIndent()
                )
                .build()
            if (!pkg.isValid) {
                Logx.e("Stage", "filamat build returned an invalid package")
                events?.onStatus("falha ao compilar o material OBJ")
                return null
            }
            val bytes = pkg.buffer
            val mat = Material.Builder().payload(bytes, bytes.remaining()).build(e)
            objMaterial = mat
            objMaterialInstance = mat.createInstance()
        } catch (t: Throwable) {
            Logx.e("Stage", "obj material build failed", t)
            events?.onStatus("falha ao compilar o material OBJ: ${t.message}")
            return null
        } finally {
            MaterialBuilder.shutdown()
        }
        return objMaterialInstance
    }

    // ------------------------------------------------------------------
    // Node ops
    // ------------------------------------------------------------------

    private fun ensureTransformInstance(entity: Int) {
        val tm: TransformManager = engine?.transformManager ?: return
        if (tm.getInstance(entity) == 0) tm.create(entity)
    }

    private fun computeMatrix(node: StageNode, out: FloatArray) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, node.position[0], node.position[1], node.position[2])
        Matrix.rotateM(out, 0, node.euler[0], 1f, 0f, 0f)
        Matrix.rotateM(out, 0, node.euler[1], 0f, 1f, 0f)
        Matrix.rotateM(out, 0, node.euler[2], 0f, 0f, 1f)
        Matrix.scaleM(out, 0, node.scale, node.scale, node.scale)
    }

    fun applyTransform(node: StageNode) {
        val tm: TransformManager = engine?.transformManager ?: return
        computeMatrix(node, node.matrix)
        val inst = tm.getInstance(node.rootEntity)
        if (inst != 0) tm.setTransform(inst, node.matrix)
    }

    fun select(id: String?) {
        if (selectedId == id) return
        selectedId = id
        events?.onSelectionChanged(id)
    }

    fun deleteSelected() {
        val n = selected() ?: return
        destroyNodeInternal(n)
        nodes.remove(n)
        selectedId = null
        events?.onSelectionChanged(null)
        events?.onNodesChanged()
        events?.onStatus("'${n.name}' excluído da cena")
    }

    fun duplicateSelected(): StageNode? {
        val n = selected() ?: return null
        // OBJ nodes duplicate in place (shared GPU buffers); glTF nodes must be re-imported.
        return if (n.kind == "obj" && n.vb != null && n.ib != null) {
            val e = engine ?: return null
            val mi = obtainObjMaterial(e) ?: return null
            val entity = EntityManager.get().create()
            RenderableManager.Builder(1)
                .material(0, mi)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, n.vb, n.ib)
                .culling(false)
                .build(e, entity)
            scene?.addEntity(entity)
            val node = StageNode(
                id = UUID.randomUUID().toString().take(8),
                name = n.name + " (cópia)",
                kind = "obj",
                fileName = n.fileName,
                rootEntity = entity,
                asset = null,
                renderEntity = entity,
                vb = n.vb, ib = n.ib,
                localMin = n.localMin.copyOf(), localMax = n.localMax.copyOf()
            )
            node.position[0] = n.position[0] + 0.4f
            node.position[1] = n.position[1]
            node.position[2] = n.position[2] + 0.4f
            node.euler[0] = n.euler[0]; node.euler[1] = n.euler[1]; node.euler[2] = n.euler[2]
            node.scale = n.scale
            node.baseFitScale = n.baseFitScale
            ensureTransformInstance(entity)
            applyTransform(node)
            nodes += node
            select(node.id)
            events?.onNodesChanged()
            node
        } else {
            events?.onStatus("para duplicar modelos glTF, reimporte o arquivo (cópia em memória não é suportada)")
            null
        }
    }

    private fun destroyNodeInternal(n: StageNode) {
        try {
            val e = engine ?: return
            val tm = e.transformManager
            tm.getInstance(n.rootEntity).takeIf { it != 0 }?.let { tm.destroy(n.rootEntity) }
            if (n.asset != null) {
                scene?.removeEntities(n.asset.entities)
                assetLoader?.destroyAsset(n.asset)
            } else {
                scene?.removeEntity(n.renderEntity)
                e.destroyEntity(n.renderEntity)
                EntityManager.get().destroy(n.renderEntity)
                n.vb?.let { e.destroyVertexBuffer(it) }
                n.ib?.let { e.destroyIndexBuffer(it) }
            }
        } catch (t: Throwable) {
            Logx.w("Stage", "node destroy failed", t)
        }
    }

    fun clearScene() {
        for (n in nodes.toList()) destroyNodeInternal(n)
        nodes.clear()
        selectedId = null
        events?.onSelectionChanged(null)
        events?.onNodesChanged()
    }

    fun resetCamera() {
        camYaw = 0.6f; camPitch = 0.32f; camDist = 4.2f
        camTarget[0] = 0f; camTarget[1] = 0f; camTarget[2] = 0f
    }

    fun snapshot(): Bitmap? = try {
        val bmp = textureView.bitmap ?: return null
        val w = 300
        val h = (bmp.height.toFloat() / bmp.width * w).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(bmp, w, h, true)
    } catch (t: Throwable) {
        Logx.w("Stage", "snapshot failed", t)
        null
    }

    // ------------------------------------------------------------------
    // Picking (project AABB corners through view·projection)
    // ------------------------------------------------------------------

    fun pickAt(xPx: Float, yPx: Float): StageNode? {
        val cam = camera ?: return null
        val viewM = FloatArray(16)
        val projD = DoubleArray(16)
        val vp = FloatArray(16)
        cam.getViewMatrix(viewM)
        cam.getProjectionMatrix(projD)
        val projM = FloatArray(16) { projD[it].toFloat() }
        Matrix.multiplyMM(vp, 0, projM, 0, viewM, 0)

        var best: StageNode? = null
        var bestArea = Float.MAX_VALUE
        val corner = FloatArray(4)
        val clip = FloatArray(4)
        for (node in nodes) {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var allBehind = true
            for (i in 0..7) {
                corner[0] = if (i and 1 == 0) node.localMin[0] else node.localMax[0]
                corner[1] = if (i and 2 == 0) node.localMin[1] else node.localMax[1]
                corner[2] = if (i and 4 == 0) node.localMin[2] else node.localMax[2]
                corner[3] = 1f
                // world = nodeMatrix * corner
                val world = FloatArray(4)
                Matrix.multiplyMV(world, 0, node.matrix, 0, corner, 0)
                Matrix.multiplyMV(clip, 0, vp, 0, world, 0)
                if (clip[3] <= 0.0001f) continue
                allBehind = false
                val nx = clip[0] / clip[3]
                val ny = clip[1] / clip[3]
                val sx = (nx * 0.5f + 0.5f) * vpWidth
                val sy = (1f - (ny * 0.5f + 0.5f)) * vpHeight
                if (sx < minX) minX = sx
                if (sx > maxX) maxX = sx
                if (sy < minY) minY = sy
                if (sy > maxY) maxY = sy
            }
            if (allBehind || minX > maxX) continue
            if (xPx in minX..maxX && yPx in minY..maxY) {
                val area = (maxX - minX) * (maxY - minY)
                if (area < bestArea) {
                    bestArea = area
                    best = node
                }
            }
        }
        return best
    }

    // ------------------------------------------------------------------
    // Touch / hand manipulation
    // ------------------------------------------------------------------

    private fun handleTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerCount = 1
                lastX = e.x; lastY = e.y
                if (mode == Mode.ORBIT) {
                    val hit = pickAt(e.x, e.y)
                    select(hit?.id)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = e.pointerCount
                if (e.pointerCount == 2) pinchDist = pinch(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.ORBIT && e.pointerCount >= 2) {
                    val d = pinch(e)
                    if (pinchDist > 0f && d > 0f) {
                        camDist = (camDist * (pinchDist / d)).coerceIn(0.6f, 40f)
                    }
                    pinchDist = d
                } else {
                    drag(e.x - lastX, e.y - lastY)
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerCount = 0
                pinchDist = 0f
            }
        }
    }

    private fun pinch(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Programmatic drag used by touch and by hand tracking (grab). */
    fun drag(dxPx: Float, dyPx: Float) {
        when (mode) {
            Mode.ORBIT -> {
                camYaw -= dxPx * 0.008f
                camPitch = (camPitch + dyPx * 0.006f).coerceIn(-1.35f, 1.35f)
            }
            Mode.MOVE -> {
                val n = selected() ?: return
                val k = camDist / vpHeight.coerceAtLeast(1)
                val rightX = cos(camYaw.toDouble()).toFloat()
                val rightZ = -sin(camYaw.toDouble()).toFloat()
                val upY = 1f
                n.position[0] += rightX * dxPx * k * 2f
                n.position[2] += rightZ * dxPx * k * 2f
                n.position[1] = (n.position[1] - dyPx * k * 2f * upY)
                applyTransform(n)
            }
            Mode.ROTATE -> {
                val n = selected() ?: return
                n.euler[1] = (n.euler[1] + dxPx * 0.4f) % 360f
                n.euler[0] = (n.euler[0] + dyPx * 0.4f).coerceIn(-180f, 180f)
                applyTransform(n)
            }
            Mode.SCALE -> {
                val n = selected() ?: return
                n.scale = (n.scale * exp((-dyPx + dxPx) * 0.004f)).coerceIn(0.02f, 60f)
                applyTransform(n)
            }
        }
    }

    /** Hand-tracking hook: pinch selects under the fingertip (stage coords). */
    fun handSelectAt(xPx: Float, yPx: Float): Boolean {
        val hit = pickAt(xPx, yPx)
        select(hit?.id)
        return hit != null
    }

    fun handDragWithSelection(dxPx: Float, dyPx: Float) {
        if (selected() != null) drag(dxPx, dyPx)
        else {
            camYaw -= dxPx * 0.008f
            camPitch = (camPitch + dyPx * 0.006f).coerceIn(-1.35f, 1.35f)
        }
    }

    fun serializeNodes(): List<Map<String, Any?>> = nodes.map { n ->
        mapOf(
            "id" to n.id,
            "name" to n.name,
            "kind" to n.kind,
            "fileName" to n.fileName,
            "px" to n.position[0].toDouble(),
            "py" to n.position[1].toDouble(),
            "pz" to n.position[2].toDouble(),
            "rx" to n.euler[0].toDouble(),
            "ry" to n.euler[1].toDouble(),
            "rz" to n.euler[2].toDouble(),
            "scale" to n.scale.toDouble()
        )
    }

    fun applySerialized(list: List<Map<String, Any?>>) {
        for (m in list) {
            val id = m["id"] as? String ?: continue
            nodes.firstOrNull { it.id == id }?.let { n ->
                n.position[0] = (m["px"] as? Number)?.toFloat() ?: n.position[0]
                n.position[1] = (m["py"] as? Number)?.toFloat() ?: n.position[1]
                n.position[2] = (m["pz"] as? Number)?.toFloat() ?: n.position[2]
                n.euler[0] = (m["rx"] as? Number)?.toFloat() ?: n.euler[0]
                n.euler[1] = (m["ry"] as? Number)?.toFloat() ?: n.euler[1]
                n.euler[2] = (m["rz"] as? Number)?.toFloat() ?: n.euler[2]
                n.scale = (m["scale"] as? Number)?.toFloat() ?: n.scale
                applyTransform(n)
            }
        }
    }

}
