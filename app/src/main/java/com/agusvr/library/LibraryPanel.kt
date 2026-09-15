package com.agusvr.library

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agusvr.R
import com.agusvr.runtime.AgusBus
import com.agusvr.runtime.AgusEvent
import com.agusvr.ui.Panels
import com.agusvr.ui.dialogs.AgusDialogs
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.util.formatBytes
import com.agusvr.util.formatDate
import com.agusvr.windows.DisposableView
import com.agusvr.windows.VrApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local library of imported games, web apps and 3D models (module:
 * AgusLibrary). Open / rename / change cover / info / delete all work on real
 * files and real persisted entries.
 */
class LibraryPanel(context: Context) : LinearLayout(context), DisposableView {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recycler: RecyclerView
    private val adapter = LibAdapter()
    private val emptyBox: View
    private var tabModels = false
    private val tabs: LinearLayout

    init {
        orientation = VERTICAL
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))

        // header
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(AgusWidgets.panelTitle(context, context.getString(R.string.library_title)))
        header.addView(View(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
        header.addView(AgusWidgets.iconButton(context, R.drawable.ic_import, context.getString(R.string.action_import)) {
            importLocal()
        })
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // tabs
        tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(tabs, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(8)
        })
        rebuildTabs()

        recycler = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2)
            clipToPadding = false
            overScrollMode = OVER_SCROLL_NEVER
            setPadding(0, context.dp(8), 0, 0)
        }
        recycler.adapter = adapter
        val contentBox = android.widget.FrameLayout(context)
        contentBox.addView(recycler, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emptyBox = AgusWidgets.emptyState(context, R.drawable.ic_library, context.getString(R.string.library_empty))
        emptyBox.visibility = View.GONE
        contentBox.addView(emptyBox, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(contentBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        LibraryRepository.load()
        scope.launch {
            LibraryRepository.items.collectLatest {
                adapter.submit(it)
            }
        }
    }

    private fun rebuildTabs() {
        tabs.removeAllViews()
        val t1 = AgusWidgets.chip(context, context.getString(R.string.library_tab_games), !tabModels) {
            tabModels = false; rebuildTabs(); adapter.submit(LibraryRepository.items.value)
        }
        val t2 = AgusWidgets.chip(context, context.getString(R.string.library_tab_models), tabModels) {
            tabModels = true; rebuildTabs(); adapter.submit(LibraryRepository.items.value)
        }
        tabs.addView(t1, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(7) })
        tabs.addView(t2)
    }

    private fun currentItems(): List<LibraryItem> =
        LibraryRepository.items.value.filter { if (tabModels) it.isModel else !it.isModel }

    private fun importLocal() {
        Panels.host?.pickFile(arrayOf("*/*")) { uri ->
            if (uri == null) return@pickFile
            scope.launch {
                val outcome = withContext(Dispatchers.IO) { ImportPipeline.importUri(context, uri) }
                when (outcome) {
                    is ImportPipeline.Outcome.Unsupported ->
                        AgusDialogs.info(context, context.getString(R.string.dlg_not_supported_title), outcome.explanation)
                    is ImportPipeline.Outcome.Failed ->
                        AgusDialogs.info(context, context.getString(R.string.err_import_failed), outcome.message)
                    else -> {}
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Item actions
    // ------------------------------------------------------------------

    fun openItem(item: LibraryItem) {
        when {
            item.type == LibraryType.WEBAPP -> Panels.host?.openApp(VrApp.BROWSER, item.url)
            item.type == LibraryType.MODEL -> Panels.host?.openApp(VrApp.MODELLAB, item.path)
            item.type == LibraryType.GAME -> {
                val url = LibraryRepository.entryUrl(item)
                if (url == null) {
                    AgusDialogs.info(context, "Erro ao abrir", item.name + ": " + context.getString(R.string.err_file_not_found))
                    return
                }
                Panels.host?.openApp(VrApp.GAME, item.id, item.name)
            }
            item.type == LibraryType.IMAGE -> Panels.host?.openApp(VrApp.IMAGEVIEW, item.path, item.name)
        }
    }

    private fun showItemMenu(item: LibraryItem) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += context.getString(R.string.action_open) to { openItem(item) }
        actions += context.getString(R.string.action_rename) to { rename(item) }
        if (!item.isModel) actions += context.getString(R.string.action_change_cover) to { changeCover(item) }
        actions += context.getString(R.string.action_info) to { info(item) }
        actions += context.getString(R.string.action_delete) to {
            AgusDialogs.confirm(
                context, context.getString(R.string.dlg_confirm_delete_title),
                "\"${item.name}\"\n\n" + context.getString(R.string.dlg_confirm_delete_body),
                context.getString(R.string.action_delete), danger = true
            ) {
                LibraryRepository.remove(item.id)
                AgusBus.toast(context.getString(R.string.toast_deleted), item.name, R.drawable.ic_trash)
            }
        }
        AgusDialogs.actionSheet(context, item.name, actions)
    }

    private fun rename(item: LibraryItem) {
        AgusDialogs.input(context, context.getString(R.string.dlg_rename_title), "Novo nome", item.name) { newName ->
            LibraryRepository.update(item.id) { it.copy(name = newName) }
            // rename the backing directory for dir-based games so files match
            scope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val p = item.path
                        if (p != null) {
                            val f = File(p)
                            if (f.isDirectory) {
                                val target = File(f.parentFile, com.agusvr.storage.FileOps.sanitizeName(newName))
                                if (!target.exists() && target.name != f.name) f.renameTo(target)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
            AgusBus.toast(context.getString(R.string.toast_renamed), newName, R.drawable.ic_edit)
        }
    }

    private fun changeCover(item: LibraryItem) {
        Panels.host?.pickImage { uri ->
            if (uri == null) return@pickImage
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val tmp = com.agusvr.storage.FileOps.importUri(context, uri, com.agusvr.storage.AgusPaths.downloads)
                        val coverFile = CoverFactory.storeUserCover(item.id, tmp)
                        tmp.delete()
                        LibraryRepository.update(item.id) { it.copy(cover = coverFile.absolutePath) }
                        CoverFactory.invalidate(item)
                        true
                    } catch (t: Throwable) {
                        false
                    }
                }
                if (!ok) AgusDialogs.info(context, "Capa", "Não foi possível usar esta imagem como capa.")
            }
        }
    }

    private fun info(item: LibraryItem) {
        val body = buildString {
            append(item.name).append("\n\n")
            append("Tipo: ").append(
                when (item.type) {
                    LibraryType.GAME -> "jogo/app web local"
                    LibraryType.WEBAPP -> "web app (internet)"
                    LibraryType.MODEL -> "modelo 3D"
                    LibraryType.IMAGE -> "imagem"
                    else -> item.type
                }
            ).append('\n')
            item.path?.let { append("Arquivo: ").append(com.agusvr.storage.AgusPaths.pathLabel(File(it))).append('\n') }
            item.entryFile?.let { append("Entrada: ").append(it).append('\n') }
            item.url?.let { append("URL: ").append(it).append('\n') }
            if (item.sizeBytes > 0) append("Tamanho: ").append(formatBytes(item.sizeBytes)).append('\n')
            append("Adicionado: ").append(formatDate(item.dateAdded)).append('\n')
            append("Origem: ").append(
                when (item.source) {
                    "builtin" -> "conteúdo do Agus VR"
                    "store", "url" -> "internet"
                    else -> "importação local"
                }
            )
            item.description?.let { append("\n").append(it) }
        }
        AgusDialogs.info(context, context.getString(R.string.action_info), body)
    }

    override fun dispose() {
        scope.cancel()
        CoverFactory.trimMemory()
    }

    // ------------------------------------------------------------------
    private inner class LibAdapter : RecyclerView.Adapter<LibVH>() {
        private var data: List<LibraryItem> = emptyList()

        fun submit(all: List<LibraryItem>) {
            data = all.filter { if (tabModels) it.isModel else !it.isModel }
            notifyDataSetChanged()
            emptyBox.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_library, parent, false)
            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(parent.context.dp(5), parent.context.dp(5), parent.context.dp(5), parent.context.dp(5))
            v.layoutParams = lp
            return LibVH(v)
        }

        override fun getItemCount(): Int = data.size
        override fun onBindViewHolder(h: LibVH, p: Int) = h.bind(data[p])
    }

    private inner class LibVH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.cover)
        private val name: TextView = view.findViewById(R.id.name)
        private val meta: TextView = view.findViewById(R.id.meta)

        init {
            name.typeface = Ui.display
            name.letterSpacing = 0.03f
            meta.typeface = Ui.mono
        }

        fun bind(item: LibraryItem) {
            name.text = item.name
            name.setTextColor(Color.parseColor("#F2F7FF"))
            meta.text = buildString {
                append(formatBytes(item.sizeBytes))
                append("  ·  ")
                append(formatDate(item.dateAdded).substringBefore(' '))
            }
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { CoverFactory.coverFor(item) }
                if (bmp != null) cover.setImageBitmap(bmp)
            }
            itemView.setOnClickListener { showItemMenu(item) }
            itemView.setOnLongClickListener { showItemMenu(item); true }
        }
    }

}
