package com.agusvr.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import com.agusvr.R
import com.agusvr.settings.SettingsRepo
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Logx
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.util.dpF
import com.agusvr.windows.DisposableView

/**
 * AGUS Browser (module: AgusBrowser) — a real WebView browser inside the VR
 * world. Home page is Google; any public https site can be visited. Hardened:
 * no file access, no content providers; window.open / target=_blank are routed
 * into the same view.
 */
class BrowserPanel(context: Context, initialUrl: String?) : LinearLayout(context), DisposableView {

    private val webView: WebView
    private val urlField: EditText
    private val statusText = AgusWidgets.monoText(context, "", AgusWidgets.FAINT, 8f)
    private val progressLine: android.widget.ProgressBar
    private var suppressUrlUpdate = false

    init {
        orientation = VERTICAL

        // ------- toolbar -------
        val toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(4))
        }
        toolbar.addView(AgusWidgets.iconButton(context, R.drawable.ic_back, "voltar") {
            if (webView.canGoBack()) webView.goBack()
        })
        toolbar.addView(AgusWidgets.iconButton(context, R.drawable.ic_forward, "avançar") {
            if (webView.canGoForward()) webView.goForward()
        }.also { (it.layoutParams as LayoutParams).marginStart = context.dp(5) })
        toolbar.addView(AgusWidgets.iconButton(context, R.drawable.ic_reload, "recarregar") {
            if (webView.progress < 100) webView.stopLoading() else webView.reload()
        }.also { (it.layoutParams as LayoutParams).marginStart = context.dp(5) })
        toolbar.addView(AgusWidgets.iconButton(context, R.drawable.ic_home, "página inicial (google)") {
            webView.loadUrl(SettingsRepo.browserHome)
        }.also { (it.layoutParams as LayoutParams).marginStart = context.dp(5) })

        urlField = AgusWidgets.input(context, context.getString(R.string.br_url_hint)).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 11f
            typeface = Ui.mono
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    navigate(text.toString().trim())
                    true
                } else false
            }
        }
        val fieldWrap = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(8)
        }
        toolbar.addView(urlField, fieldWrap)
        addView(toolbar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressLine = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressDrawable = context.getDrawable(R.drawable.progress_agus)
            maxHeight = context.dp(4)
            minHeight = context.dp(3)
            max = 100
            progress = 0
        }
        addView(progressLine, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ------- webview -------
        webView = createWebView()
        addView(webView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        statusText.setPadding(context.dp(12), context.dp(4), context.dp(12), context.dp(6))
        addView(statusText, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val home = SettingsRepo.browserHome
        val start = when {
            !initialUrl.isNullOrBlank() -> initialUrl
            else -> home
        }
        webView.loadUrl(start)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(context)
        wv.setBackgroundColor(Color.WHITE)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            userAgentString = userAgentString + " AgusVR/1.0"
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return if (URLUtil.isNetworkUrl(url)) {
                    false // load inside our webview
                } else {
                    // external schemes (mailto:, intent:, tel:…) → hand to Android
                    try {
                        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                    } catch (t: Throwable) {
                        statusText.text = "não há app para abrir: $url"
                    }
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!suppressUrlUpdate) urlField.setText(url ?: "")
                statusText.text = "carregando…"
                statusText.setTextColor(AgusWidgets.FAINT)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                statusText.text = view?.title ?: url ?: ""
                statusText.setTextColor(AgusWidgets.FAINT)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    statusText.text = "erro: ${error?.description ?: "não foi possível abrir a página"}"
                    statusText.setTextColor(AgusWidgets.RED)
                }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressLine.progress = newProgress
                progressLine.alpha = if (newProgress >= 100) 0f else 1f
            }

            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                Logx.d("Browser", "console: ${msg?.message()}")
                return true
            }
        }
        return wv
    }

    private fun navigate(raw: String) {
        var target = raw.trim()
        if (target.isEmpty()) return
        if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) {
            target = if (target.contains('.') && !target.contains(' ')) "https://$target"
            else "https://www.google.com/search?q=" + android.net.Uri.encode(target)
        }
        webView.loadUrl(target)
    }

    fun onBackPressedInBrowser(): Boolean {
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    override fun dispose() {
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        } catch (t: Throwable) {
            Logx.w("Browser", "webview destroy failed", t)
        }
    }
}
