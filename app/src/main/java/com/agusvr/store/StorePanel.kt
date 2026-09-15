package com.agusvr.store

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agusvr.R
import com.agusvr.library.ImportPipeline
import com.agusvr.library.LibraryRepository
import com.agusvr.runtime.AgusBus
import com.agusvr.runtime.AgusEvent
import com.agusvr.ui.Panels
import com.agusvr.ui.dialogs.AgusDialogs
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.util.dpF
import com.agusvr.windows.DisposableView
import com.agusvr.windows.VrApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AGUS STORE (module: AgusStore) — spatial app/game store with categories,
 * search, installable cards, internet imports and local file imports (SAF).
 *
 * Install is REAL: builtin content is copied from assets, remote ZIPs are
 * downloaded with progress + integrity checks and extracted, web entries are
 * stored as browser shortcuts. Unsupported formats are refused with an
 * explanation — never faked.
 */
class StorePanel(context: Context) : LinearLayout(context), DisposableView {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val items = mutableListOf<CatalogItem>()
    private var category = "Tudo"
    private var query = ""
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: StoreAdapter
    private val chipBox = LinearLayout(context)
    private val progressByKey = mutableMapOf<String, Float>()

    init {
        orientation = VERTICAL
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
        buildHeader()
        buildChips()
        recycler = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2)
            setPadding(0, context.dp(8), 0, 0)
            clipToPadding = false
            overScrollMode = OVER_SCROLL_NEVER
        }
        adapter = StoreAdapter()
        recycler.adapter = adapter
        addView(recycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        items.clear()
        items.addAll(StoreRepository.load(context))
        refresh()
    }

    // ------------------------------------------------------------------
    private fun buildHeader() {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(AgusWidgets.panelTitle(context, "AGUS STORE"))
        val spacer = View(context).apply { layoutParams = LayoutParams(0, 1, 1f) }
        header.addView(spacer)
        header.addView(AgusWidgets.iconButton(context, R.drawable.ic_link, "Adicionar da internet") {
            showAddFromUrl()
        })
        val importBtn = AgusWidgets.iconButton(context, R.drawable.ic_import, "Importar arquivo local") {
            importLocal()
        }
        (importBtn.layoutParams as LayoutParams).marginStart = context.dp(6)
        header.addView(importBtn)
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val search = AgusWidgets.input(context, context.getString(R.string.store_search_hint))
        search.inputType = android.text.InputType.TYPE_CLASS_TEXT
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                refresh()
            }
        })
        addView(search, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(9)
        })
    }

    private fun buildChips() {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }
        chipBox.orientation = HORIZONTAL
        chipBox.setPadding(0, context.dp(8), 0, 0)
        scroll.addView(chipBox)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rebuildChips()
    }

    private fun rebuildChips() {
        chipBox.removeAllViews()
        for (cat in StoreRepository.getCategories()) {
            val chip = AgusWidgets.chip(context, cat, cat == category) {
                category = cat
                rebuildChips()
                refresh()
            }
            chipBox.addView(chip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.dp(7)
            })
        }
    }

    private fun refresh() {
        adapter.submit()
    }

    private fun visibleItems(): List<CatalogItem> = items.filter {
        (category == "Tudo" || it.category == category) &&
            (query.isEmpty() || it.name.contains(query, true) || it.tagline.contains(query, true))
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private fun showAddFromUrl() {
        val (_, field) = AgusDialogs.input(
            context, context.getString(R.string.store_url_dialog_title),
            context.getString(R.string.store_url_hint)
        ) { url ->
            handleUrlAdd(normalizeUrl(url))
        }
        field.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun normalizeUrl(raw: String): String =
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"

    private fun handleUrlAdd(url: String) {
        val uri = try {
            Uri.parse(url)
        } catch (t: Throwable) {
            AgusDialogs.info(context, "URL inválida", "Não foi possível interpretar este endereço.")
            return
        }
        if (uri.scheme != "http" && uri.scheme != "https") {
            AgusDialogs.info(context, "URL inválida", "Use endereços http:// ou https://.")
            return
        }
        val name = uri.lastPathSegment?.substringBeforeLast('.')?.replace('_', ' ')?.replace('-', ' ')
            ?.replaceFirstChar { it.uppercase() } ?: (uri.host ?: "Conteúdo")
        val classified = ImportPipeline.classifyUrl(url)
        if (classified == null) {
            // It's a web page → save as web app shortcut and offer to open it.
            ImportPipeline.installWebApp(url, name, null)
            AgusDialogs.confirm(context, "Web app adicionado",
                "\"$name\" foi salvo na Biblioteca como web app.\n\nAbrir agora no Navegador Agus?",
                "Abrir agora") {
                Panels.host?.openApp(VrApp.BROWSER, url)
            }
            adapter.submit()
            return
        }
        startDownload(null, url, classified.first, name)
    }

    private fun importLocal() {
        Panels.host?.pickFile(arrayOf("*/*")) { uri ->
            if (uri == null) return@pickFile
            scope.launch {
                val result = withContext(Dispatchers.IO) { ImportPipeline.importUri(context, uri) }
                showOutcome(result)
                adapter.submit()
            }
        }
    }

    private fun startDownload(item: CatalogItem?, url: String, fileName: String, title: String) {
        val key = item?.id ?: url
        progressByKey[key] = 0f
        Panels.host?.requestNotificationPermission { /* download works either way */ }
        DownloadEngine.download(
            key = key,
            url = url,
            fileName = fileName,
            title = "AGUS STORE · $title",
            onProgress = { frac, _ ->
                progressByKey[key] = frac
                adapter.notifyKey(key)
            },
            onDone = { result ->
                progressByKey.remove(key)
                adapter.notifyKey(key)
                val file = result.file
                if (file != null) {
                    val outcome = ImportPipeline.installDownloadedFile(file, title, item?.id)
                    showOutcome(outcome)
                } else {
                    AgusDialogs.info(context, context.getString(R.string.err_download_failed), result.error ?: "Erro desconhecido.")
                    AgusBus.toast(context.getString(R.string.err_download_failed), result.error, R.drawable.ic_download, AgusEvent.ToastKind.ERROR)
                }
                adapter.submit()
            }
        )
    }

    private fun showOutcome(outcome: ImportPipeline.Outcome) {
        when (outcome) {
            is ImportPipeline.Outcome.Installed -> { /* toast already emitted by pipeline */ }
            is ImportPipeline.Outcome.Unsupported -> {
                AgusDialogs.info(
                    context, context.getString(R.string.dlg_not_supported_title),
                    outcome.explanation
                )
            }
            is ImportPipeline.Outcome.Failed -> {
                AgusDialogs.info(context, "Importação falhou", outcome.message)
            }
        }
    }

    private fun onInstallClick(item: CatalogItem) {
        val installed = StoreRepository.installedItem(item.id)
        if (installed != null) {
            // open it
            when {
                installed.type == com.agusvr.library.LibraryType.WEBAPP ->
                    Panels.host?.openApp(VrApp.BROWSER, installed.url)
                installed.type == com.agusvr.library.LibraryType.MODEL ->
                    Panels.host?.openApp(VrApp.MODELLAB, installed.path)
                else -> Panels.host?.openApp(VrApp.GAME, installed.id, installed.name)
            }
            return
        }
        when (item.type) {
            "builtin_html" -> scope.launch {
                val asset = item.asset ?: return@launch
                val outcome = withContext(Dispatchers.IO) {
                    ImportPipeline.installBuiltinHtml(context, asset, item.name, item.id)
                }
                showOutcome(outcome)
                adapter.submit()
            }
            "builtin_models" -> scope.launch {
                val outcome = withContext(Dispatchers.IO) { ImportPipeline.installBuiltinModels(context) }
                showOutcome(outcome)
                adapter.submit()
            }
            "remote_zip" -> {
                val url = item.url
                if (url == null) {
                    AgusDialogs.info(context, "Item inválido", "Este item do catálogo não tem URL de download.")
                    return
                }
                startDownload(item, url, fileNameFor(url), item.name)
            }
            "web" -> {
                val url = item.url ?: return
                ImportPipeline.installWebApp(url, item.name, item.id)
                Panels.host?.openApp(VrApp.BROWSER, url)
                adapter.submit()
            }
            else -> AgusDialogs.info(
                context, context.getString(R.string.dlg_not_supported_title),
                "Tipo de item desconhecido no catálogo: ${item.type}."
            )
        }
    }

    private fun fileNameFor(url: String): String {
        val last = url.substringAfterLast('/').substringBefore('?')
        return last.ifEmpty { "download.zip" }
    }

    private fun showItemInfo(item: CatalogItem) {
        val installed = StoreRepository.installedItem(item.id)
        val body = buildString {
            append(item.name).append("\n\n")
            append(item.tagline).append("\n\n")
            append("Categoria: ").append(item.category).append('\n')
            append("Tamanho: ").append(item.sizeLabel).append('\n')
            append("Autor: ").append(item.author).append('\n')
            append("Licença: ").append(item.license).append('\n')
            append("Tipo: ").append(
                when (item.type) {
                    "builtin_html" -> "conteúdo embutido (HTML offline)"
                    "builtin_models" -> "modelos 3D embutidos"
                    "remote_zip" -> "download ZIP do projeto oficial"
                    "web" -> "web app (abre no navegador)"
                    else -> item.type
                }
            ).append('\n')
            if (installed != null) append("\nInstalado em ").append(com.agusvr.util.formatDate(installed.dateAdded))
            item.url?.let { append("\n\nFonte: ").append(it) }
        }
        AgusDialogs.info(context, "Informações", body)
    }

    override fun dispose() {
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private inner class StoreAdapter : RecyclerView.Adapter<StoreVH>() {

        private var data: List<CatalogItem> = emptyList()

        fun submit() {
            data = visibleItems()
            notifyDataSetChanged()
        }

        fun notifyKey(key: String) {
            val idx = data.indexOfFirst { it.id == key }
            if (idx >= 0) notifyItemChanged(idx)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoreVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_store, parent, false)
            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(parent.context.dp(5), parent.context.dp(5), parent.context.dp(5), parent.context.dp(5))
            v.layoutParams = lp
            return StoreVH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(h: StoreVH, position: Int) = h.bind(data[position])
    }

    private inner class StoreVH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: FrameLayout = view.findViewById(R.id.cover)
        private val coverIcon: ImageView = view.findViewById(R.id.coverIcon)
        private val categoryTag: TextView = view.findViewById(R.id.categoryTag)
        private val name: TextView = view.findViewById(R.id.name)
        private val tagline: TextView = view.findViewById(R.id.tagline)
        private val size: TextView = view.findViewById(R.id.size)
        private val action: TextView = view.findViewById(R.id.action)
        private val progressWrap: View = view.findViewById(R.id.progressWrap)
        private val progressFill: View = view.findViewById(R.id.progressFill)

        init {
            name.typeface = Ui.display
            name.letterSpacing = 0.04f
            tagline.typeface = Ui.ui
            size.typeface = Ui.mono
            action.typeface = Ui.display
            action.letterSpacing = 0.08f
            categoryTag.typeface = Ui.mono
        }

        @SuppressLint("NotifyDataSetChanged")
        fun bind(item: CatalogItem) {
            val accent = try {
                Color.parseColor(item.accent)
            } catch (_: Throwable) {
                AgusWidgets.CYAN
            }
            name.text = item.name
            tagline.text = item.tagline
            size.text = item.sizeLabel

            cover.background = GradientDrawable().apply {
                cornerRadius = context.dpF(12)
                colors = intArrayOf(
                    Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)),
                    Color.parseColor("#101A2E")
                )
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(context.dp(1), Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
            coverIcon.setImageResource(iconForType(item.type))
            coverIcon.setColorFilter(accent)
            categoryTag.text = item.category.uppercase()
            categoryTag.setTextColor(accent)
            categoryTag.background = GradientDrawable().apply {
                cornerRadius = context.dpF(999)
                setColor(Color.parseColor("#66050A14"))
                setStroke(1, Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }

            val progress = progressByKey[item.id]
            if (progress != null) {
                progressWrap.visibility = View.VISIBLE
                val w = (progressWrap.width * progress.coerceIn(0f, 1f)).toInt()
                progressFill.layoutParams = (progressFill.layoutParams as FrameLayout.LayoutParams).apply { width = if (w > 0) w else 1 }
                action.text = if (progress < 0) "…" else "${(progress * 100).toInt()}%"
                action.isEnabled = false
                action.alpha = 0.6f
            } else {
                progressWrap.visibility = View.GONE
                action.isEnabled = true
                action.alpha = 1f
                val installed = LibraryRepository.findByOrigin(item.id)
                action.text = when {
                    installed != null -> "ABRIR"
                    item.isRemote -> "INSTALAR"
                    item.isWeb -> "ABRIR"
                    else -> "OBTER"
                }
            }

            action.setOnClickListener { onInstallClick(item) }
            itemView.setOnClickListener { showItemInfo(item) }
            itemView.setOnLongClickListener { showItemInfo(item); true }
        }

        private fun iconForType(type: String): Int = when (type) {
            "builtin_models" -> R.drawable.ic_cube_small
            "remote_zip" -> R.drawable.ic_zip
            "web" -> R.drawable.ic_browser
            else -> R.drawable.ic_play
        }
    }
}
