package com.agusvr.files

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agusvr.R
import com.agusvr.library.ImportPipeline
import com.agusvr.runtime.AgusBus
import com.agusvr.storage.AgusPaths
import com.agusvr.storage.FileOps
import com.agusvr.ui.Panels
import com.agusvr.ui.dialogs.AgusDialogs
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.util.dirSize
import com.agusvr.util.extOf
import com.agusvr.util.formatBytes
import com.agusvr.util.formatDate
import com.agusvr.windows.DisposableView
import com.agusvr.windows.VrApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AGUS File Manager (module: AgusFileManager) — a real file manager over the
 * AgusVR sandbox: navigate, open, import (SAF), copy, move, delete, rename,
 * info, new folder. It can also hand ZIPs to an external manager like
 * ZArchiver through a public Android intent when the user has it installed —
 * nothing third-party is embedded or modified.
 */
class FilesPanel(context: Context) : LinearLayout(context), DisposableView {

    private enum class Mode { BROWSE, COPY_DEST, MOVE_DEST }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentDir: File = AgusPaths.root
    private var mode = Mode.BROWSE
    private var pendingFile: File? = null

    private val crumb: TextView
    private val modeBanner: TextView
    private val recycler: RecyclerView
    private val adapter = FileAdapter()
    private val upButton: View

    init {
        orientation = VERTICAL
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))

        // header
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        upButton = AgusWidgets.iconButton(context, R.drawable.ic_up, "pasta acima") { goUp() }
        header.addView(upButton)
        crumb = AgusWidgets.monoText(context, "", AgusWidgets.CYAN, 9f).apply {
            maxLines = 1
            setPadding(context.dp(10), 0, context.dp(6), 0)
        }
        header.addView(crumb, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(AgusWidgets.iconButton(context, R.drawable.ic_plus, context.getString(R.string.action_new_folder)) { newFolder() })
        header.addView(AgusWidgets.iconButton(context, R.drawable.ic_import, context.getString(R.string.action_import)) { importHere() }
            .also { (it.layoutParams as LayoutParams).marginStart = context.dp(5) })
        header.addView(AgusWidgets.iconButton(context, R.drawable.ic_zip, context.getString(R.string.files_zarchiver)) { openExternalArchiveApp() }
            .also { (it.layoutParams as LayoutParams).marginStart = context.dp(5) })
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        modeBanner = AgusWidgets.monoText(context, "", AgusWidgets.AMBER, 9f).apply {
            visibility = View.GONE
            setPadding(context.dp(4), context.dp(8), context.dp(4), 0)
        }
        addView(modeBanner, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            overScrollMode = OVER_SCROLL_NEVER
            setPadding(0, context.dp(8), 0, context.dp(4))
        }
        recycler.adapter = adapter
        addView(recycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        refresh()
    }

    // ------------------------------------------------------------------
    private fun refresh() {
        crumb.text = AgusPaths.pathLabel(currentDir)
        upButton.isEnabled = currentDir != AgusPaths.root
        upButton.alpha = if (currentDir == AgusPaths.root) 0.35f else 1f
        val entries = currentDir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()
        adapter.submit(entries)
    }

    private fun goUp() {
        val parent = currentDir.parentFile
        if (parent != null && currentDir != AgusPaths.root && parent.absolutePath.startsWith(AgusPaths.root.absolutePath)) {
            currentDir = parent
            refresh()
        } else if (currentDir != AgusPaths.root) {
            currentDir = AgusPaths.root
            refresh()
        }
    }

    private fun navigate(dir: File) {
        currentDir = dir
        refresh()
    }

    private fun newFolder() {
        AgusDialogs.input(context, context.getString(R.string.action_new_folder), "Nome da pasta") { name ->
            try {
                FileOps.createFolder(currentDir, name)
                refresh()
                AgusBus.toast("Pasta criada", name, R.drawable.ic_folder)
            } catch (t: Throwable) {
                AgusDialogs.info(context, "Erro", t.message ?: "Não foi possível criar a pasta.")
            }
        }
    }

    private fun importHere() {
        Panels.host?.pickFile(arrayOf("*/*")) { uri ->
            if (uri == null) return@pickFile
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        FileOps.importUri(context, uri, currentDir).name
                    } catch (t: Throwable) {
                        null
                    }
                }
                if (result != null) {
                    AgusBus.toast("Arquivo importado", result, R.drawable.ic_import)
                    refresh()
                } else {
                    AgusDialogs.info(context, context.getString(R.string.err_import_failed),
                        "Não foi possível copiar este arquivo para ${AgusPaths.pathLabel(currentDir)}.")
                }
            }
        }
    }

    private fun openExternalArchiveApp() {
        // Legal integration: launch an installed external archive manager via a
        // public intent (e.g. ZArchiver). We never embed or modify it.
        val candidates = listOf("com.devsense.zarchiver", "com.devsense.zarchiverpro")
        val pm = context.packageManager
        for (pkg in candidates) {
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    AgusBus.toast("Abrindo ZArchiver", "integração via intent público do Android", R.drawable.ic_zip)
                    return
                } catch (t: Throwable) {
                    AgusDialogs.info(context, "ZArchiver", "O app está instalado mas recusou a abertura (${t.message}).")
                    return
                }
            }
        }
        AgusDialogs.info(context, context.getString(R.string.files_zarchiver),
            context.getString(R.string.files_zarchiver_missing) +
                "\n\nVocê pode instalar o ZArchiver pela Play Store; o Agus VR passa a usá-lo automaticamente para ZIPs.")
    }

    // ------------------------------------------------------------------
    // File actions
    // ------------------------------------------------------------------

    private fun onFileClick(f: File) {
        when (mode) {
            Mode.COPY_DEST, Mode.MOVE_DEST -> {
                if (f.isDirectory) {
                    completeTransfer(f)
                } else {
                    AgusDialogs.info(context, "Destino", "Escolha uma PASTA como destino da operação.")
                }
                return
            }
            Mode.BROWSE -> {
                if (f.isDirectory) navigate(f) else openFile(f)
            }
        }
    }

    private fun completeTransfer(destDir: File) {
        val src = pendingFile ?: return
        val isMove = mode == Mode.MOVE_DEST
        mode = Mode.BROWSE
        pendingFile = null
        modeBanner.visibility = View.GONE
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                try {
                    val target = AgusPaths.unique(destDir, src.name)
                    if (isMove) {
                        if (src.isDirectory) FileOps.moveDir(src, target) else FileOps.moveFile(src, target)
                    } else {
                        if (src.isDirectory) FileOps.copyDir(src, target) else FileOps.copyFile(src, target)
                    }
                    target.name
                } catch (t: Throwable) {
                    null
                }
            }
            if (name != null) {
                AgusBus.toast(if (isMove) context.getString(R.string.toast_moved) else context.getString(R.string.toast_copied),
                    "$name → ${AgusPaths.pathLabel(destDir)}", R.drawable.ic_copy)
            } else {
                AgusDialogs.info(context, "Operação falhou", "Não foi possível ${if (isMove) "mover" else "copiar"} para esta pasta.")
            }
            refresh()
        }
    }

    private fun openFile(f: File) {
        val ext = extOf(f.name)
        when {
            ext in ImportPipeline.RUNNABLE_WEB_EXTS ->
                Panels.host?.openApp(VrApp.GAME, "file://${f.absolutePath}", f.nameWithoutExtension)
            ext in ImportPipeline.MODEL_EXTS ->
                Panels.host?.openApp(VrApp.MODELLAB, f.absolutePath)
            ext in ImportPipeline.IMAGE_EXTS ->
                Panels.host?.openApp(VrApp.IMAGEVIEW, f.absolutePath, f.name)
            ext in ImportPipeline.TEXT_EXTS ->
                Panels.host?.openApp(VrApp.TEXTVIEW, f.absolutePath, f.name)
            ext == "zip" -> AgusDialogs.actionSheet(context, f.name, listOf(
                "Extrair aqui" to { extractZip(f) },
                "Instalar na Biblioteca (se houver HTML)" to { installZipToLibrary(f) },
                "Abrir com app externo" to { openWithExternal(f) }
            ))
            else -> AgusDialogs.info(context, context.getString(R.string.dlg_not_supported_title),
                ".${ext.ifEmpty { "?" }} — " + context.getString(R.string.err_format_unsupported))
        }
    }

    private fun extractZip(f: File) {
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val dest = File(f.parentFile, f.nameWithoutExtension)
                    FileOps.extractZip(f, dest)
                    "Extraído para ${AgusPaths.pathLabel(dest)}"
                } catch (t: Throwable) {
                    t.message ?: "Falha ao extrair"
                }
            }
            AgusDialogs.info(context, "Extrair ZIP", msg)
            refresh()
        }
    }

    private fun installZipToLibrary(f: File) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                ImportPipeline.installZip(f, f.nameWithoutExtension, "import")
            }
            when (outcome) {
                is ImportPipeline.Outcome.Installed ->
                    AgusDialogs.confirm(context, "Instalado", "\"${outcome.item.name}\" foi adicionado à Biblioteca.\n\nAbrir agora?", "Abrir") {
                        Panels.host?.openApp(VrApp.GAME, outcome.item.id, outcome.item.name)
                    }
                is ImportPipeline.Outcome.Failed -> AgusDialogs.info(context, "ZIP", outcome.message)
                is ImportPipeline.Outcome.Unsupported -> AgusDialogs.info(context, "ZIP", outcome.explanation)
            }
        }
    }

    private fun openWithExternal(f: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", f
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/zip")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Abrir ZIP com…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            AgusDialogs.info(context, "Abrir com…",
                "Nenhum app disponível para abrir este ZIP (${t.message}).\nExtraia pelo próprio Agus VR.")
        }
    }

    private fun showFileMenu(f: File) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (f.isFile) actions += context.getString(R.string.action_open) to { openFile(f) }
        actions += context.getString(R.string.action_copy) to {
            pendingFile = f
            mode = Mode.COPY_DEST
            modeBanner.text = "COPIAR \"${f.name}\" → ${context.getString(R.string.files_move_mode)}"
            modeBanner.visibility = View.VISIBLE
        }
        actions += context.getString(R.string.action_move) to {
            pendingFile = f
            mode = Mode.MOVE_DEST
            modeBanner.text = "MOVER \"${f.name}\" → ${context.getString(R.string.files_move_mode)}"
            modeBanner.visibility = View.VISIBLE
        }
        actions += context.getString(R.string.action_rename) to {
            AgusDialogs.input(context, context.getString(R.string.dlg_rename_title), "Novo nome", f.name) { newName ->
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        try {
                            FileOps.rename(f, newName)
                            null
                        } catch (t: Throwable) {
                            t.message
                        }
                    }
                    if (msg != null) AgusDialogs.info(context, "Renomear", msg)
                    else AgusBus.toast(context.getString(R.string.toast_renamed), newName, R.drawable.ic_edit)
                    refresh()
                }
            }
        }
        actions += context.getString(R.string.action_info) to { showFileInfo(f) }
        actions += context.getString(R.string.action_delete) to {
            AgusDialogs.confirm(context, context.getString(R.string.dlg_confirm_delete_title),
                "\"${f.name}\"\n\n" + context.getString(R.string.dlg_confirm_delete_body),
                context.getString(R.string.action_delete), danger = true) {
                scope.launch {
                    withContext(Dispatchers.IO) { FileOps.delete(f) }
                    AgusBus.toast(context.getString(R.string.toast_deleted), f.name, R.drawable.ic_trash)
                    refresh()
                }
            }
        }
        actions += "Cancelar" to {}
        AgusDialogs.actionSheet(context, f.name, actions)
    }

    private fun showFileInfo(f: File) {
        val body = buildString {
            append("Nome: ").append(f.name).append("\n\n")
            append("Tipo: ").append(if (f.isDirectory) "pasta" else "arquivo ${extOf(f.name).uppercase().ifEmpty { "?" }}").append('\n')
            append("Local: ").append(AgusPaths.pathLabel(f)).append('\n')
            append("Tamanho: ").append(
                if (f.isDirectory) formatBytes(dirSize(f)) else formatBytes(f.length())
            ).append('\n')
            append("Modificado: ").append(formatDate(f.lastModified())).append('\n')
            append("Legível: ").append(if (f.canRead()) "sim" else "não")
        }
        AgusDialogs.info(context, context.getString(R.string.action_info), body)
    }

    override fun dispose() {
        scope.cancel()
    }

    // ------------------------------------------------------------------
    private inner class FileAdapter : RecyclerView.Adapter<FileVH>() {
        private var data: List<File> = emptyList()

        fun submit(list: List<File>) {
            data = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(parent.context.dp(2), parent.context.dp(3), parent.context.dp(2), parent.context.dp(3))
            v.layoutParams = lp
            return FileVH(v)
        }

        override fun getItemCount(): Int = data.size
        override fun onBindViewHolder(h: FileVH, p: Int) = h.bind(data[p])
    }

    private inner class FileVH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val name: TextView = view.findViewById(R.id.name)
        private val meta: TextView = view.findViewById(R.id.meta)
        private val chevron: ImageView = view.findViewById(R.id.chevron)

        init {
            name.typeface = Ui.ui
            meta.typeface = Ui.mono
        }

        fun bind(f: File) {
            name.text = f.name
            val ext = extOf(f.name)
            if (f.isDirectory) {
                icon.setImageResource(R.drawable.ic_folder)
                icon.setColorFilter(Color.parseColor("#FFC24D"))
                meta.text = "pasta · ${f.listFiles()?.size ?: 0} itens"
                chevron.visibility = View.VISIBLE
            } else {
                val (res, color) = when {
                    ext in ImportPipeline.RUNNABLE_WEB_EXTS -> R.drawable.ic_browser to "#5EFFB1"
                    ext == "zip" -> R.drawable.ic_zip to "#FFC24D"
                    ext in ImportPipeline.MODEL_EXTS -> R.drawable.ic_cube_small to "#9D6BFF"
                    ext in ImportPipeline.IMAGE_EXTS -> R.drawable.ic_image to "#FF5EC7"
                    ext in ImportPipeline.TEXT_EXTS -> R.drawable.ic_file to "#A9B8D8"
                    else -> R.drawable.ic_file to "#668FA3C8"
                }
                icon.setImageResource(res)
                icon.setColorFilter(Color.parseColor(color))
                meta.text = "${formatBytes(f.length())} · ${formatDate(f.lastModified())}"
                chevron.visibility = View.GONE
            }
            itemView.setOnClickListener {
                Ui.tick(itemView)
                onFileClick(f)
            }
            itemView.setOnLongClickListener { showFileMenu(f); true }
        }
    }
}
