package com.agusvr.modellab

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.agusvr.R
import com.agusvr.storage.AgusPaths
import com.agusvr.storage.FileOps
import com.agusvr.ui.Panels
import com.agusvr.ui.dialogs.AgusDialogs
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Logx
import com.agusvr.util.dp
import com.agusvr.util.extOf
import com.agusvr.util.formatDate
import com.agusvr.windows.DisposableView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * AGUS MODEL LAB (module: AgusModelLab).
 *
 * Import OBJ / GLB / glTF, manipulate objects (move / rotate / scale /
 * duplicate / delete) by touch or hand gestures, orbit the camera and save
 * projects (name, cover image, date and the full transform list) into
 * AgusVR/Projects. Everything runs on a real Filament renderer.
 */
class ModelLabPanel(
    context: Context,
    private val initialModelPath: String? = null
) : LinearLayout(context), DisposableView, FilamentStage.Events {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private val stage: FilamentStage
    private val statusText: TextView
    private val selectionText: TextView
    private val modeSegment: LinearLayout

    private var currentProjectId: String? = null
    private var currentProjectName: String? = null

    private val stageTexture = TextureView(context)

    init {
        orientation = VERTICAL
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))

        // ---- header ----
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(AgusWidgets.panelTitle(context, context.getString(R.string.ml_title)))
        header.addView(View(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
        header.addView(AgusWidgets.iconButton(context, R.drawable.ic_import, "Importar modelo") { importModel() })
        header.addView(spaced(AgusWidgets.iconButton(context, R.drawable.ic_cube_small, "Modelos de exemplo") { showSamples() }))
        header.addView(spaced(AgusWidgets.iconButton(context, R.drawable.ic_orbit, "Recentrar câmera") { stage.resetCamera() }))
        header.addView(spaced(AgusWidgets.iconButton(context, R.drawable.ic_trash, "Limpar cena") { confirmClear() }))
        addView(header, lpWrap())

        // ---- mode row ----
        val modeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeSegment = AgusWidgets.segmented(context, listOf(
            context.getString(R.string.ml_mode_orbit),
            context.getString(R.string.ml_mode_move),
            context.getString(R.string.ml_mode_rotate),
            context.getString(R.string.ml_mode_scale)
        ), 0) { idx ->
            stage.mode = when (idx) {
                1 -> FilamentStage.Mode.MOVE
                2 -> FilamentStage.Mode.ROTATE
                3 -> FilamentStage.Mode.SCALE
                else -> FilamentStage.Mode.ORBIT
            }
            onStatus(when (stage.mode) {
                FilamentStage.Mode.ORBIT -> "modo órbita: arraste para girar a câmera, pinça para zoom"
                FilamentStage.Mode.MOVE -> "modo mover: arraste o objeto selecionado"
                FilamentStage.Mode.ROTATE -> "modo girar: arraste para rotacionar o objeto"
                FilamentStage.Mode.SCALE -> "modo escala: arraste para redimensionar o objeto"
            })
        }
        modeRow.addView(modeSegment, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modeRow.addView(AgusWidgets.iconButton(context, R.drawable.ic_copy, "Duplicar") {
            if (stage.duplicateSelected() == null && stage.selected() == null) {
                onStatus("selecione um objeto para duplicar")
            }
        }.withMargin(context.dp(8)))
        modeRow.addView(AgusWidgets.iconButton(context, R.drawable.ic_trash, "Excluir") {
            if (stage.selected() == null) onStatus("selecione um objeto para excluir")
            else AgusDialogs.confirm(context, "Excluir objeto",
                "Excluir '${stage.selected()?.name}' da cena? O arquivo original não é apagado.",
                "Excluir", danger = true) { stage.deleteSelected() }
        })
        addView(modeRow, lpWrap().apply { topMargin = context.dp(8) })

        selectionText = AgusWidgets.monoText(context, "nenhum objeto selecionado", AgusWidgets.CYAN, 9f)
        addView(selectionText, lpWrap().apply { topMargin = context.dp(6) })

        // ---- stage ----
        stage = FilamentStage(stageTexture)
        stage.events = this
        stageTexture.background = context.getDrawable(R.drawable.bg_content)
        addView(stageTexture, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = context.dp(8); bottomMargin = context.dp(6)
        })

        // ---- status ----
        statusText = AgusWidgets.monoText(context, "pronto — importe um modelo OBJ, GLB ou glTF")
        addView(statusText, lpWrap())

        // ---- projects row ----
        val projectRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        projectRow.addView(AgusWidgets.ghostButton(context, context.getString(R.string.ml_project_save), R.drawable.ic_save) { saveProject() })
        projectRow.addView(spaced(AgusWidgets.ghostButton(context, context.getString(R.string.ml_project_load), R.drawable.ic_folder) { openProjectDialog() }))
        addView(projectRow, lpWrap().apply { topMargin = context.dp(8) })

        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                stage.start()
                startSelectionTicker()
                initialModelPath?.let { p ->
                    scope.launch { loadModelFileAwait(File(p)) }
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                stopSelectionTicker()
                stage.destroy()
            }
        })
    }

    // ------------------------------------------------------------------
    // FilamentStage.Events
    // ------------------------------------------------------------------

    override fun onStatus(message: String) {
        handler.post { statusText.text = message }
    }

    override fun onSelectionChanged(nodeId: String?) {
        handler.post { updateSelectionLabel() }
    }

    override fun onNodesChanged() {
        // nodes list changed (load/delete/duplicate/clear) — labels refresh via ticker
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    private fun importModel() {
        val host = Panels.host
        if (host == null) {
            onStatus("host indisponível para importar")
            return
        }
        host.pickFile(
            arrayOf("model/obj", "model/gltf-binary", "model/gltf+json", "model/vnd.mts",
                "text/plain", "application/octet-stream", "*/*")
        ) { uri ->
            if (uri == null) return@pickFile
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { FileOps.importUri(context, uri, AgusPaths.models) }
                }
                result.onSuccess { file ->
                    onStatus("importado para AgusVR/Models/${file.name}")
                    loadModelFile(file)
                }.onFailure { t ->
                    val msg = (t as? FileOps.FileOpException)?.message ?: t.message ?: "erro desconhecido"
                    onStatus("falha na importação: $msg")
                }
            }
        }
    }

    private fun showSamples() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val modelFiles = (AgusPaths.models.listFiles()?.toList() ?: emptyList())
            .filter { extOf(it.name) in setOf("obj", "glb", "gltf") }
            .sortedBy { it.name }
        for (f in modelFiles) {
            actions += (f.name to { loadModelFile(f) })
        }
        // bundled samples from assets
        val assetSamples = try {
            context.assets.list("samples")?.toList() ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        for (name in assetSamples) {
            if (extOf(name) !in setOf("obj", "glb", "gltf")) continue
            if (modelFiles.any { it.name == name }) continue
            actions += ("$name (exemplo embutido)" to { copyAssetSample(name) })
        }
        if (actions.isEmpty()) {
            AgusDialogs.info(context, "Modelos de exemplo",
                "Nenhum modelo disponível ainda. Importe um arquivo OBJ, GLB ou glTF pelo botão de importação, ou baixe modelos pela AGUS STORE.")
            return
        }
        AgusDialogs.actionSheet(context, "Carregar modelo", actions)
    }

    private fun copyAssetSample(name: String) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = AgusPaths.unique(AgusPaths.models, name)
                    context.assets.open("samples/$name").use { input -> dest.outputStream().use { input.copyTo(it) } }
                    dest
                }
            }
            file.onSuccess { loadModelFile(it) }
                .onFailure { onStatus("não foi possível copiar o exemplo: ${it.message}") }
        }
    }

    fun loadModelFile(file: File) {
        if (!file.exists()) {
            onStatus("arquivo não encontrado: ${file.name}")
            return
        }
        when (extOf(file.name)) {
            "glb" -> stage.loadGlbFile(file)
            "gltf" -> stage.loadGltfFile(file)
            "obj" -> {
                onStatus("lendo ${file.name}…")
                scope.launch {
                    val parsed = withContext(Dispatchers.IO) {
                        runCatching { ObjParser.parse(file) }
                    }
                    parsed.onSuccess { mesh -> stage.loadObjMesh(mesh, file.nameWithoutExtension, file.name) }
                        .onFailure { t ->
                            val msg = (t as? ObjParseException)?.message ?: "OBJ inválido: ${t.message}"
                            onStatus(msg ?: "OBJ inválido")
                        }
                }
            }
            else -> onStatus("Formato não suportado pelo Agus Model Lab (use OBJ, GLB ou glTF).")
        }
    }

    private fun confirmClear() {
        if (stage.allNodes().isEmpty()) {
            onStatus("a cena já está vazia")
            return
        }
        AgusDialogs.confirm(context, "Limpar cena",
            "Remover todos os ${stage.allNodes().size} objeto(s) da cena? Projetos salvos não são afetados.",
            "Limpar", danger = true) {
            stage.clearScene()
            onStatus("cena limpa")
        }
    }

    // ------------------------------------------------------------------
    // Projects
    // ------------------------------------------------------------------

    private fun saveProject() {
        if (stage.allNodes().isEmpty()) {
            onStatus("nada para salvar — carregue um modelo primeiro")
            return
        }
        AgusDialogs.input(
            context,
            context.getString(R.string.ml_project_save),
            "Nome do projeto",
            currentProjectName ?: ""
        ) { name ->
            scope.launch {
                val snapshot: Bitmap? = stage.snapshot()
                val png: ByteArray? = snapshot?.let { bmp ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ByteArrayOutputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                                out.toByteArray()
                            }
                        }.getOrNull()
                    }
                }
                val project = withContext(Dispatchers.IO) {
                    ProjectRepository.save(name, stage.serializeNodes(), png, currentProjectId)
                }
                if (project != null) {
                    currentProjectId = project.id
                    currentProjectName = project.name
                    onStatus("projeto '$name' salvo em AgusVR/Projects (${stage.allNodes().size} objeto(s))")
                } else {
                    onStatus("falha ao salvar o projeto")
                }
            }
        }
    }

    private fun openProjectDialog() {
        val projects = ProjectRepository.all()
        if (projects.isEmpty()) {
            AgusDialogs.info(context, context.getString(R.string.ml_project_load),
                "Nenhum projeto salvo ainda. Monte uma cena e use 'Salvar projeto'.")
            return
        }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        for (p in projects) {
            actions += ("▶  ${p.name} · ${formatDate(p.updatedAt)} · ${p.nodes.length()} obj" to { restoreProject(p) })
            actions += ("✕  Excluir '${p.name}'" to {
                AgusDialogs.confirm(context, "Excluir projeto",
                    "Excluir o projeto '${p.name}' e sua capa? Essa ação não pode ser desfeita.",
                    "Excluir", danger = true) {
                    if (ProjectRepository.delete(p)) {
                        onStatus("projeto '${p.name}' excluído")
                        if (currentProjectId == p.id) {
                            currentProjectId = null
                            currentProjectName = null
                        }
                    } else onStatus("falha ao excluir o projeto")
                }
            })
        }
        AgusDialogs.actionSheet(context, "Projetos salvos", actions)
    }

    private fun restoreProject(p: ModelProject) {
        stage.clearScene()
        currentProjectId = p.id
        currentProjectName = p.name
        val specs = (0 until p.nodes.length()).mapNotNull { p.nodes.optJSONObject(it) }
        if (specs.isEmpty()) {
            onStatus("projeto '${p.name}' não contém objetos")
            return
        }
        scope.launch {
            onStatus("restaurando projeto '${p.name}'…")
            var ok = 0
            for (spec in specs) {
                val fileName = spec.optString("fileName")
                val file = fileName.takeIf { it.isNotEmpty() }?.let { resolveModelFile(it) }
                if (file == null) {
                    onStatus("arquivo '$fileName' do projeto não encontrado — objeto pulado")
                    continue
                }
                val before = stage.allNodes().size
                loadModelFileAwait(file)
                val nodes = stage.allNodes()
                if (nodes.size > before) {
                    applySpecToNode(nodes.last(), spec)
                    ok++
                }
            }
            onStatus("projeto '${p.name}' restaurado: $ok/${specs.size} objeto(s)")
        }
    }

    /** Loads a model and suspends until its node exists and its resources are ready. */
    private suspend fun loadModelFileAwait(file: File) {
        // wait for the renderer to come up (window may still be animating open)
        var waited = 0
        while (!stage.isReady && waited < 60) {
            kotlinx.coroutines.delay(100)
            waited++
        }
        when (extOf(file.name)) {
            "obj" -> {
                val parsed = withContext(Dispatchers.IO) { runCatching { ObjParser.parse(file) } }
                parsed.onSuccess { mesh -> stage.loadObjMesh(mesh, file.nameWithoutExtension, file.name) }
                    .onFailure { t ->
                        onStatus((t as? ObjParseException)?.message ?: "OBJ inválido: ${t.message}")
                    }
            }
            "glb" -> {
                stage.loadGlbFile(file)
                awaitStageIdle()
            }
            "gltf" -> {
                stage.loadGltfFile(file)
                awaitStageIdle()
            }
            else -> onStatus("Formato não suportado pelo Agus Model Lab (use OBJ, GLB ou glTF).")
        }
    }

    private suspend fun awaitStageIdle() {
        var waited = 0
        while (stage.isBusy() && waited < 200) {
            kotlinx.coroutines.delay(100)
            waited++
        }
    }

    private fun applySpecToNode(node: StageNode, spec: JSONObject) {
        node.position[0] = spec.optDouble("px", node.position[0].toDouble()).toFloat()
        node.position[1] = spec.optDouble("py", node.position[1].toDouble()).toFloat()
        node.position[2] = spec.optDouble("pz", node.position[2].toDouble()).toFloat()
        node.euler[0] = spec.optDouble("rx", node.euler[0].toDouble()).toFloat()
        node.euler[1] = spec.optDouble("ry", node.euler[1].toDouble()).toFloat()
        node.euler[2] = spec.optDouble("rz", node.euler[2].toDouble()).toFloat()
        node.scale = spec.optDouble("scale", node.scale.toDouble()).toFloat()
        stage.applyTransform(node)
    }

    private fun resolveModelFile(name: String): File? {
        val direct = File(name)
        if (direct.isAbsolute && direct.exists()) return direct
        for (dir in listOf(AgusPaths.models, AgusPaths.downloads, AgusPaths.library)) {
            val f = File(dir, name)
            if (f.exists()) return f
        }
        return null
    }

    // ------------------------------------------------------------------
    // Selection ticker
    // ------------------------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            updateSelectionLabel()
            handler.postDelayed(this, 400)
        }
    }

    private fun startSelectionTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 400)
    }

    private fun stopSelectionTicker() {
        handler.removeCallbacks(ticker)
    }

    private fun updateSelectionLabel() {
        val n = stage.selected()
        selectionText.text = if (n == null) {
            "nenhum objeto selecionado · ${stage.allNodes().size} na cena"
        } else {
            "selecionado: ${n.name}  pos(%.2f, %.2f, %.2f)  rot(%.0f°, %.0f°, %.0f°)  escala %.2f×".format(
                n.position[0], n.position[1], n.position[2],
                n.euler[0], n.euler[1], n.euler[2], n.scale
            )
        }
    }

    // ------------------------------------------------------------------

    private fun spaced(v: View): View = v.withMargin(context.dp(4))

    private fun View.withMargin(startPx: Int): View {
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = startPx
        }
        return this
    }

    private fun lpWrap() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun dispose() {
        stopSelectionTicker()
        stage.destroy()
        scope.cancel()
        Logx.d("ModelLab", "panel disposed")
    }
}
