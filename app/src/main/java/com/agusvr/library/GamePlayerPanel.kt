package com.agusvr.library

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.agusvr.R
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Logx
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.windows.DisposableView

/**
 * Secure internal WebView player for library games (module: AgusLibrary).
 *
 * File access is enabled ONLY for app-private content (extracted ZIP projects
 * need file:// and local fetches). Everything else is locked down: no content
 * provider access, no popups, errors are surfaced honestly.
 */
class GamePlayerPanel(context: Context, param: String?) : LinearLayout(context), DisposableView {

    private var webView: WebView? = null
    private val status: TextView

    init {
        orientation = VERTICAL
        status = AgusWidgets.monoText(context, "carregando…", AgusWidgets.FAINT, 8.5f).apply {
            setPadding(context.dp(12), context.dp(5), context.dp(12), context.dp(5))
            maxLines = 1
        }

        // param is either a library item id or a direct file:// URL (file manager)
        val item = param?.takeIf { !it.startsWith("file://") }?.let { LibraryRepository.find(it) }
        val name: String
        val url: String?
        if (item != null) {
            name = item.name
            url = LibraryRepository.entryUrl(item)
        } else if (param?.startsWith("file://") == true) {
            name = java.io.File(param.removePrefix("file://")).nameWithoutExtension
            url = param
        } else {
            name = ""
            url = null
        }
        if (url == null) {
            addView(AgusWidgets.emptyState(context, R.drawable.ic_play,
                "Conteúdo não encontrado.\nO arquivo pode ter sido removido do armazenamento."),
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status)
        } else {
            val bar = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
            }
            bar.addView(AgusWidgets.iconButton(context, R.drawable.ic_reload, "reiniciar") {
                webView?.reload()
            })
            val title = AgusWidgets.bodyText(context, name, AgusWidgets.TEXT, 11.5f).apply {
                typeface = Ui.display
                letterSpacing = 0.06f
                maxLines = 1
                setPadding(context.dp(10), 0, context.dp(10), 0)
            }
            bar.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(AgusWidgets.iconButton(context, R.drawable.ic_info, "informações") {
                com.agusvr.ui.dialogs.AgusDialogs.info(
                    context, name,
                    "Entrada: $url\nTipo: ${item?.description ?: "conteúdo web local"}\n" +
                        "Executado no WebView interno do Agus VR (sandbox do aplicativo)."
                )
            })
            addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val wv = createWebView()
            webView = wv
            addView(wv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            wv.loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(context)
        wv.setBackgroundColor(Color.parseColor("#04060C"))
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Needed for local ZIP projects (file:// + local fetch/XHR).
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString + " AgusVR/1.0"
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    status.text = "erro ao carregar: ${error?.description ?: "desconhecido"}"
                    status.setTextColor(AgusWidgets.RED)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                status.text = "pronto · ${view?.title ?: url ?: ""}"
                status.setTextColor(AgusWidgets.FAINT)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                Logx.d("GamePlayer", "console: ${msg?.message()} @${msg?.sourceId()}:${msg?.lineNumber()}")
                return true
            }
        }
        wv.setOnLongClickListener { true } // disable text selection handles inside games
        wv.isLongClickable = false
        return wv
    }

    override fun dispose() {
        try {
            webView?.let {
                it.stopLoading()
                it.loadUrl("about:blank")
                (it.parent as? ViewGroup)?.removeView(it)
                it.removeAllViews()
                it.destroy()
            }
        } catch (t: Throwable) {
            Logx.w("GamePlayer", "webview destroy failed", t)
        }
        webView = null
    }
}
